package dev.materione.plugin.gui;

import org.bukkit.event.inventory.InventoryClickEvent;
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
     */
    @SuppressWarnings("this-escape") // createInventory only records the holder
    protected PluginGui(int size, net.kyori.adventure.text.Component title) {
        this.inventory = org.bukkit.Bukkit.createInventory(this, size, title);
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
}
