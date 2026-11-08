package dev.materione.plugin.gui;

import dev.materione.api.gui.GuiContainer;
import java.util.Optional;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Base for every Materione menu (runtime-systems §8's virtual-inventory
 * substrate): the gui object IS the {@link InventoryHolder}, so clicks are
 * routed by holder identity — never by title matching, which breaks on
 * locale, color codes and collisions. Subclasses pass {@code this} to
 * {@code Bukkit.createInventory(this, …)} so the created inventory carries
 * them as its holder; {@link GuiListener} then delivers only the clicks
 * that land inside this inventory.
 *
 * <p>The window is created from the container profile's {@code open} descriptor (gui.md §12.6): by size, or by
 * {@code InventoryType}. Both use the legacy {@code createInventory(holder, …, Component)} path, so the holder
 * and the title transport serve every container. No code here asks which container it is.
 *
 * <p>One viewer per instance: a gui is created fresh per open (per player,
 * per page) and holds no global state — Folia-safe by construction.
 */
public abstract class PluginGui implements InventoryHolder {

    private final Inventory inventory;

    /**
     * Creates this gui's own inventory with {@code this} as holder — done
     * here, in the base constructor body, because Java forbids passing
     * {@code this} from a subclass super-call while creation itself never
     * calls back into the (still-uninitialized) subclass.
     *
     * @param open the container profile's window descriptor; the caller checks {@link #inventoryType} first when it
     *     is made by type, so this constructor is never handed a type the server cannot create
     * @throws IllegalArgumentException when {@code open} is made by type and {@link #inventoryType} is empty
     */
    @SuppressWarnings("this-escape") // createInventory only records the holder
    protected PluginGui(GuiContainer.Open open, net.kyori.adventure.text.Component title) {
        if (open.inventoryType() == null) {
            this.inventory = org.bukkit.Bukkit.createInventory(this, open.size(), title);
        } else {
            InventoryType type = inventoryType(open).orElseThrow(() ->
                    new IllegalArgumentException("no creatable inventory type named " + open.inventoryType()));
            this.inventory = org.bukkit.Bukkit.createInventory(this, type, title);
        }
    }

    /**
     * The Bukkit {@code InventoryType} a type-made window names, or empty when this server has no such type or it
     * cannot be created (gui.md §12.6, {@code MTR-0098}). A size-made window has none, and the empty result is
     * not an error for it: check {@link GuiContainer.Open#inventoryType()} first. Reads the Bukkit enum, which
     * needs a running server, so it is checked on the server (the harness IT), not in the plain-JVM tests.
     */
    public static Optional<InventoryType> inventoryType(GuiContainer.Open open) {
        if (open.inventoryType() == null) {
            return Optional.empty();
        }
        try {
            InventoryType type = InventoryType.valueOf(open.inventoryType());
            return type.isCreatable() ? Optional.of(type) : Optional.empty();
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    /**
     * A click inside this gui's top inventory. The event is already
     * cancelled; handlers decide what it means (navigation, pickup, …).
     */
    protected abstract void onClick(InventoryClickEvent event);

    /**
     * A click outside this gui's window. The event is already cancelled, and most guis ignore it, so
     * the default does nothing; a gui that reacts to it (logic.md §6.2, {@code gui.click_outside})
     * overrides it.
     */
    protected void onOutsideClick(InventoryClickEvent event) {
        // no gui reacts by default
    }
}
