package dev.materione.plugin.gui;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.platform.items.MaterioneStacks;
import dev.materione.plugin.MaterionePlugin;
import dev.materione.plugin.items.Give;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /materione browser} — the admin acquisition surface: one screen
 * per pack (grouped by namespace, which is the pack id by construction),
 * each a paginated grid of that pack's live items. Left-click picks one
 * item onto the cursor, shift-click hands over a full stack.
 *
 * <p>Entries snapshot from the live registries at open time; a reload
 * changes what future opens show (re-open to refresh). Tooling surface —
 * author-defined menus (M6) will render through the same gui kit rather
 * than through this class.
 */
public final class ContentBrowser {

    private final MaterionePlugin plugin;

    public ContentBrowser(MaterionePlugin plugin) {
        this.plugin = plugin;
    }

    /** Opens the pack picker for one viewer on their region thread. */
    public void open(Player viewer) {
        plugin.scheduling().executeEntity(plugin, viewer, () -> showPicker(viewer));
    }

    private void showPicker(Player viewer) {
        // namespace == pack id: ParseStage names every resource after its pack
        Map<String, List<RuntimeItem>> byPack = new LinkedHashMap<>();
        for (RuntimeItem item : plugin.pipeline().runtime().registries().items()) {
            byPack.computeIfAbsent(item.id().namespace(), pack -> new ArrayList<>()).add(item);
        }
        List<Map.Entry<String, List<RuntimeItem>>> packs = byPack.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        PickerGui gui = new PickerGui(viewer, packs);
        viewer.openInventory(gui.getInventory());
    }

    private final class PickerGui extends PaginatedGui {

        private final Player viewer;
        private final List<Map.Entry<String, List<RuntimeItem>>> packs;

        PickerGui(Player viewer, List<Map.Entry<String, List<RuntimeItem>>> packs) {
            super("Materione packs", new Pagination(packs.size(), PER_PAGE));
            this.viewer = viewer;
            this.packs = packs;
            rerender();
        }

        @Override
        protected void fillContent(int page) {
            var inv = getInventory();
            for (int slot = 0, index = startIndex(page);
                    slot < PER_PAGE && index < packs.size(); slot++, index++) {
                var entry = packs.get(index);
                ItemStack icon = new ItemStack(Material.CHEST);
                icon.editMeta(meta -> {
                    meta.displayName(Component.text(entry.getKey(), NamedTextColor.AQUA));
                    meta.lore(List.of(Component.text(
                            entry.getValue().size() + " item(s)", NamedTextColor.GRAY)));
                });
                inv.setItem(slot, icon);
            }
        }

        @Override
        protected void onItem(int slotInPage, boolean shift, InventoryClickEvent event) {
            int index = startIndex(page()) + slotInPage;
            if (index < packs.size()) {
                showItems(viewer, packs.get(index));
            }
        }

        @Override
        protected String infoText() {
            return "packs — page " + (page() + 1) + "/" + pages();
        }
    }

    private final class ItemsGui extends PaginatedGui {

        private final Player viewer;
        private final String pack;
        private final List<RuntimeItem> items;

        ItemsGui(Player viewer, String pack, List<RuntimeItem> items) {
            super("Materione — " + pack, new Pagination(items.size(), PER_PAGE));
            this.viewer = viewer;
            this.pack = pack;
            this.items = items;
            rerender();
        }

        @Override
        protected void fillContent(int page) {
            var inv = getInventory();
            for (int slot = 0, index = startIndex(page);
                    slot < PER_PAGE && index < items.size(); slot++, index++) {
                inv.setItem(slot,
                        MaterioneStacks.build(MaterioneStacks.specOf(items.get(index))));
            }
        }

        @Override
        protected void onItem(int slotInPage, boolean shift, InventoryClickEvent event) {
            int index = startIndex(page()) + slotInPage;
            if (index >= items.size()) {
                return;
            }
            RuntimeItem item = items.get(index);
            if (shift) {
                ItemStack template = MaterioneStacks.build(MaterioneStacks.specOf(item));
                int amount = template.getMaxStackSize();
                Give.deliver(viewer, template, amount);
                confirm(viewer, item.id(), amount);
            } else if (event.getView().getCursor() == null
                    || event.getView().getCursor().getType().isAir()) {
                event.getView().setCursor(MaterioneStacks.build(MaterioneStacks.specOf(item)));
                confirm(viewer, item.id(), 1);
            }
        }

        @Override
        protected boolean hasBack() {
            return true;
        }

        @Override
        protected void back() {
            showPicker(viewer);
        }

        @Override
        protected String infoText() {
            return pageCountSuffix().isEmpty() ? pack : pack + pageCountSuffix();
        }

        private String pageCountSuffix() {
            return pages() == 1 ? "" : " — page " + (page() + 1) + "/" + pages();
        }
    }

    private void showItems(Player viewer, Map.Entry<String, List<RuntimeItem>> pack) {
        ItemsGui gui = new ItemsGui(viewer, pack.getKey(), pack.getValue());
        viewer.openInventory(gui.getInventory());
    }

    private static void confirm(Player player, NamespacedId id, int amount) {
        player.sendMessage(Component.text("Given ", NamedTextColor.GREEN)
                .append(Component.text(id.toString(), NamedTextColor.AQUA))
                .append(Component.text(" ×" + amount, NamedTextColor.GREEN)));
    }
}
