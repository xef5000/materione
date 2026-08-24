package dev.materione.plugin.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/**
 * A 54-slot gui whose last row navigates pages over the 45 content slots
 * above it — the shared shape of every browsing surface (the M2 admin
 * browser today, author-defined menus later). Subclasses supply the
 * content for the current page and what one item click means; this class
 * owns page state, the nav row, and rerendering.
 *
 * <p>Instances are created per open (one viewer); {@code page} starts at 0.
 * The constructor passes {@code this} to {@code createInventory} before
 * subclass fields exist — safe because creation only records the holder,
 * it never calls into it; overridden methods run first after rerender().
 */
public abstract class PaginatedGui extends PluginGui {

    /** Content slots: rows 0–4. The bottom row (45–53) is navigation. */
    public static final int PER_PAGE = 45;
    protected static final int SLOT_PREV = 45;
    protected static final int SLOT_BACK = 48;
    protected static final int SLOT_INFO = 49;
    protected static final int SLOT_CLOSE = 50;
    protected static final int SLOT_NEXT = 53;

    private final Pagination pagination;
    private int page;

    protected PaginatedGui(String title, Pagination pagination) {
        super(54, Component.text(title));
        this.pagination = pagination;
    }

    /** The page actually shown after clamping. */
    protected final int page() {
        return pagination.clamp(page);
    }

    /** Total pages (for "x/y" displays). */
    protected final int pages() {
        return pagination.pageCount();
    }

    /** Source-list index of this page's first entry. */
    protected final int startIndex(int page) {
        return pagination.start(page);
    }

    /** Fills content slots for {@code page} ({@link #PER_PAGE} slots, rows 0–4). */
    protected abstract void fillContent(int page);

    /** One click on a content slot ({@code slotInPage} is 0–44 within the page). */
    protected abstract void onItem(int slotInPage, boolean shift, InventoryClickEvent event);

    /** Text shown on the info item (pack name, page x/y, …). */
    protected abstract String infoText();

    /** Whether a back action exists (renders the back button when true). */
    protected boolean hasBack() {
        return false;
    }

    /** Navigates back; only called when {@link #hasBack()} is true. */
    protected void back() {}

    /** Refills content + nav row for the current page; subclasses call it once after constructing. */
    protected final void rerender() {
        var inv = getInventory();
        inv.clear();
        int current = page();
        fillContent(current);
        if (pagination.hasPrevious(current)) {
            inv.setItem(SLOT_PREV, button(Material.ARROW, "Previous page"));
        }
        if (hasBack()) {
            inv.setItem(SLOT_BACK, button(Material.BOOK, "Back"));
        }
        inv.setItem(SLOT_INFO, button(Material.NAME_TAG, infoText()));
        inv.setItem(SLOT_CLOSE, button(Material.BARRIER, "Close"));
        if (pagination.hasNext(current)) {
            inv.setItem(SLOT_NEXT, button(Material.ARROW, "Next page"));
        }
    }

    @Override
    protected final void onClick(InventoryClickEvent event) {
        int slot = event.getSlot();
        switch (slot) {
            case SLOT_PREV -> {
                if (pagination.hasPrevious(page())) {
                    page--;
                    rerender();
                }
            }
            case SLOT_NEXT -> {
                if (pagination.hasNext(page())) {
                    page++;
                    rerender();
                }
            }
            case SLOT_BACK -> back();
            case SLOT_CLOSE -> event.getWhoClicked().closeInventory();
            default -> {
                if (slot >= 0 && slot < PER_PAGE) {
                    onItem(slot, event.isShiftClick(), event);
                }
            }
        }
    }

    private static ItemStack button(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> meta.displayName(Component.text(name, NamedTextColor.YELLOW)));
        return stack;
    }
}
