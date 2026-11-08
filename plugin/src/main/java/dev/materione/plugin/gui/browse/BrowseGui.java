package dev.materione.plugin.gui.browse;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.platform.items.MaterioneStacks;
import dev.materione.platform.text.ItemText;
import dev.materione.plugin.MaterionePlugin;
import dev.materione.plugin.gui.Pagination;
import dev.materione.plugin.gui.PluginGui;
import dev.materione.plugin.items.Give;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/**
 * The in-game content browser (M6, {@code /materione browse}): a 54-slot window with three views of one registry
 * snapshot. The first screen lists the categories (each shown as its first item). A category, or a search, lists its
 * rows (each shown as the item itself). The bottom row navigates: previous and next page, search, back, a page
 * indicator and close.
 *
 * <p>Taking. A left click hands one of the row's item to the player, a shift-click a full stack, both through
 * {@link Give#deliver}, so nothing lands on the cursor and no item can be duplicated by a drop, a number key or a drag:
 * the click router cancels those into this window (PluginGui, {@code GuiListener}), and this handler acts only on the
 * four click types it names. A take requires {@link BrowseAccess#TAKE}; without it the click says so and gives nothing.
 *
 * <p>Search. The search button tells the player the command to type ({@code /materione browse <words>}), which opens
 * the window already filtered. An in-window prompt is the GUI runtime's {@code prompt} action and needs a window
 * authored as a GUI (see the PR's open questions); this window is built in code.
 *
 * <p>One viewer per instance, created fresh per open (Folia-safe: no state is shared between viewers). The rows are a
 * snapshot of the registries at open time: a reload changes what the next open shows.
 */
public final class BrowseGui extends PluginGui {

    /** Content slots: rows 0 to 4 of the window. The bottom row is navigation. */
    public static final int PER_PAGE = 45;

    private static final int SLOT_PREV = 45;
    private static final int SLOT_SEARCH = 46;
    private static final int SLOT_BACK = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_CLOSE = 50;
    private static final int SLOT_NEXT = 53;
    private static final int SLOT_EMPTY = 22;

    private final MaterionePlugin plugin;
    private final Player viewer;
    private final RuntimeRegistries registries;
    private final List<BrowseEntry> rows;
    private final List<BrowseCatalog.Category> categories;
    /** The search text, or null when the window is not searching. */
    private String query;
    /** The category key, or null on the categories view (and during a search). */
    private String category;
    private int page;

    private BrowseGui(MaterionePlugin plugin, Player viewer, RuntimeRegistries registries,
            List<BrowseEntry> rows, String query) {
        super(54, Component.text("Materione · Browse"));
        this.plugin = plugin;
        this.viewer = viewer;
        this.registries = registries;
        this.rows = rows;
        this.categories = BrowseCatalog.categories(rows);
        this.query = BrowseCatalog.words(query).isEmpty() ? null : query.strip();
        rerender();
    }

    /**
     * Opens the browser for one viewer on their region thread. {@code query} filters it at once; null or blank opens
     * the categories. The caller has checked nothing: this checks {@link BrowseAccess#OPEN} itself.
     */
    public static void open(MaterionePlugin plugin, Player viewer, String query) {
        plugin.scheduling().executeEntity(plugin, viewer, () -> {
            if (!viewer.hasPermission(BrowseAccess.OPEN)) {
                viewer.sendMessage(Component.text(BrowseAccess.OPEN_DENIED, NamedTextColor.RED));
                return;
            }
            RuntimeRegistries registries = plugin.pipeline().runtime().registries();
            List<BrowseEntry> rows = BrowseCatalog.build(registries);
            plugin.getLogger().info("MATERIONE-BROWSE-OPEN rows=" + rows.size()
                    + " searched=" + (query != null && !query.isBlank()));
            viewer.openInventory(new BrowseGui(plugin, viewer, registries, rows, query).getInventory());
        });
    }

    private boolean onCategories() {
        return query == null && category == null;
    }

    /** The rows the window shows on the entries view: the search result, or the category's rows. */
    private List<BrowseEntry> currentRows() {
        if (query != null) {
            return BrowseCatalog.search(rows, query);
        }
        return BrowseCatalog.inCategory(rows, category);
    }

    private int total() {
        return onCategories() ? categories.size() : currentRows().size();
    }

    /** Refills the content and the navigation row for the current page. */
    private void rerender() {
        var inv = getInventory();
        inv.clear();
        Pagination pagination = new Pagination(total(), PER_PAGE);
        page = pagination.clamp(page);
        int start = pagination.start(page);
        int end = pagination.end(page);
        if (onCategories()) {
            if (categories.isEmpty()) {
                empty("No content is loaded", "Check /materione doctor, then reload");
            }
            for (int index = start; index < end; index++) {
                inv.setItem(index - start, categoryTile(categories.get(index)));
            }
        } else {
            List<BrowseEntry> shown = currentRows();
            if (shown.isEmpty()) {
                empty(query != null ? "Nothing matches \"" + query + "\"" : "This category is empty",
                        query != null ? "Try fewer or other words" : "Go back to the categories");
            }
            for (int index = start; index < end; index++) {
                inv.setItem(index - start, entryTile(shown.get(index)));
            }
        }
        if (pagination.hasPrevious(page)) {
            inv.setItem(SLOT_PREV, button(Material.ARROW, "Previous page"));
        }
        inv.setItem(SLOT_SEARCH, button(Material.COMPASS, "Search"));
        if (!onCategories()) {
            inv.setItem(SLOT_BACK, button(Material.BOOK, "Back to categories"));
        }
        inv.setItem(SLOT_INFO, button(Material.NAME_TAG, infoText(pagination)));
        inv.setItem(SLOT_CLOSE, button(Material.BARRIER, "Close"));
        if (pagination.hasNext(page)) {
            inv.setItem(SLOT_NEXT, button(Material.ARROW, "Next page"));
        }
    }

    private String infoText(Pagination pagination) {
        if (onCategories()) {
            return categories.size() + " categor" + (categories.size() == 1 ? "y" : "ies")
                    + " · " + rows.size() + " entries";
        }
        int shown = currentRows().size();
        String head = query != null
                ? "Search \"" + query + "\" · " + shown + " found"
                : categoryLabel() + " · " + shown + " entr" + (shown == 1 ? "y" : "ies");
        return pagination.pageCount() == 1 ? head : head + " · page " + (page + 1) + "/" + pagination.pageCount();
    }

    private String categoryLabel() {
        for (BrowseCatalog.Category candidate : categories) {
            if (candidate.key().equals(category)) {
                return candidate.label();
            }
        }
        return category;
    }

    private void empty(String title, String hint) {
        ItemStack stack = new ItemStack(Material.BARRIER);
        stack.editMeta(meta -> {
            meta.displayName(ItemText.nonItalic(Component.text(title, NamedTextColor.YELLOW)));
            meta.lore(List.of(ItemText.nonItalic(Component.text(hint, NamedTextColor.GRAY))));
        });
        getInventory().setItem(SLOT_EMPTY, stack);
    }

    private ItemStack categoryTile(BrowseCatalog.Category group) {
        ItemStack stack = iconOf(group.icon(), Material.CHEST);
        stack.editMeta(meta -> {
            meta.displayName(ItemText.nonItalic(Component.text(group.label(), NamedTextColor.AQUA)));
            meta.lore(List.of(ItemText.nonItalic(Component.text(
                    group.count() + " entr" + (group.count() == 1 ? "y" : "ies") + " · click to open",
                    NamedTextColor.GRAY))));
        });
        return stack;
    }

    private ItemStack entryTile(BrowseEntry entry) {
        RuntimeItem live = entry.item() == null ? null : registries.item(entry.item()).orElse(null);
        ItemStack stack = live == null
                ? new ItemStack(Material.BARRIER)
                : MaterioneStacks.build(MaterioneStacks.specOf(live));
        List<Component> extra = new ArrayList<>();
        extra.add(ItemText.nonItalic(Component.text(entry.id().toString(), NamedTextColor.DARK_GRAY)));
        extra.add(ItemText.nonItalic(Component.text("category: " + entry.category(), NamedTextColor.GRAY)));
        if (entry.kind() != BrowseEntry.Kind.ITEM) {
            extra.add(ItemText.nonItalic(Component.text(
                    entry.kind() == BrowseEntry.Kind.BLOCK ? "custom block" : "furniture", NamedTextColor.GRAY)));
        }
        if (live == null) {
            extra.add(ItemText.nonItalic(Component.text(
                    entry.item() == null
                            ? "no placement item — place it with /materione dev place " + entry.id()
                            : "placement item is not live (reload the content)",
                    NamedTextColor.RED)));
        } else {
            extra.add(ItemText.nonItalic(Component.text(
                    "Click: take one · Shift-click: a full stack", NamedTextColor.YELLOW)));
        }
        stack.editMeta(meta -> {
            var lore = new ArrayList<Component>();
            if (meta.lore() != null) {
                lore.addAll(meta.lore());
            }
            lore.addAll(extra);
            meta.lore(lore);
        });
        return stack;
    }

    private ItemStack iconOf(NamespacedId id, Material fallback) {
        RuntimeItem live = id == null ? null : registries.item(id).orElse(null);
        return live == null ? new ItemStack(fallback) : MaterioneStacks.build(MaterioneStacks.specOf(live));
    }

    @Override
    protected void onClick(InventoryClickEvent event) {
        int slot = event.getSlot();
        ClickType click = event.getClick();
        // only the four clicks that mean something here; a number key, a drop, a middle or double click does nothing
        if (!(click.isLeftClick() || click.isRightClick())) {
            return;
        }
        int size = total();
        Pagination pagination = new Pagination(size, PER_PAGE);
        switch (slot) {
            case SLOT_PREV -> {
                if (pagination.hasPrevious(page)) {
                    page--;
                    rerender();
                }
            }
            case SLOT_NEXT -> {
                if (pagination.hasNext(page)) {
                    page++;
                    rerender();
                }
            }
            case SLOT_BACK -> {
                if (!onCategories()) {
                    // a search and a category both return to the categories
                    query = null;
                    category = null;
                    page = 0;
                    rerender();
                }
            }
            case SLOT_SEARCH -> viewer.sendMessage(Component.text("Type ", NamedTextColor.GRAY)
                    .append(Component.text("/materione browse <words>", NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.suggestCommand("/materione browse ")))
                    .append(Component.text(" to search by id or name.", NamedTextColor.GRAY)));
            case SLOT_CLOSE -> viewer.closeInventory();
            default -> {
                if (slot >= 0 && slot < PER_PAGE) {
                    int index = pagination.start(page) + slot;
                    if (index < size) {
                        clickRow(index, click.isShiftClick());
                    }
                }
            }
        }
    }

    private void clickRow(int index, boolean shift) {
        if (onCategories()) {
            category = categories.get(index).key();
            page = 0;
            rerender();
            return;
        }
        give(currentRows().get(index), shift);
    }

    /** Hands one row's item to the viewer: one, or a full stack on a shift-click. */
    private void give(BrowseEntry entry, boolean full) {
        if (!viewer.hasPermission(BrowseAccess.TAKE)) {
            viewer.sendMessage(Component.text(BrowseAccess.TAKE_DENIED, NamedTextColor.RED));
            return;
        }
        if (entry.item() == null) {
            viewer.sendMessage(Component.text(entry.id() + " has no placement item — place it with:",
                    NamedTextColor.RED));
            viewer.sendMessage(Component.text("/materione dev place " + entry.id(), NamedTextColor.YELLOW));
            return;
        }
        RuntimeItem live = registries.item(entry.item()).orElse(null);
        if (live == null) {
            viewer.sendMessage(Component.text("placement item " + entry.item() + " is not live (reload the content)",
                    NamedTextColor.RED));
            return;
        }
        ItemStack template = MaterioneStacks.build(MaterioneStacks.specOf(live));
        int amount = full ? template.getMaxStackSize() : 1;
        Give.deliver(viewer, template, amount);
        plugin.getLogger().info("MATERIONE-BROWSE-GET id=" + entry.id() + " amount=" + amount);
        Component message = Component.text("Given ", NamedTextColor.GREEN)
                .append(Component.text(entry.id().toString(), NamedTextColor.AQUA))
                .append(Component.text(" ×" + amount, NamedTextColor.GREEN));
        if (entry.kind() != BrowseEntry.Kind.ITEM) {
            message = message.append(Component.text(" — places " + entry.id(), NamedTextColor.GRAY));
        }
        viewer.sendMessage(message);
    }

    private static ItemStack button(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> meta.displayName(ItemText.nonItalic(Component.text(name, NamedTextColor.YELLOW))));
        return stack;
    }
}
