package dev.materione.plugin.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * The single click router for every {@link PluginGui} (registered once,
 * static wiring like the other listeners). Identification is holder
 * identity — a gui is whatever {@code InventoryHolder} the top inventory
 * was created with — so renames, colors and locales can never break
 * routing.
 *
 * <p>Everything that touches a gui's view is cancelled: clicks in the top
 * inventory are handed to the gui, and clicks/drag in the player's own
 * inventory are suppressed too, so viewers cannot shift items into or out
 * of a menu. Gui handlers run on the viewer's region thread (where the
 * event fired) and see an already-cancelled event.
 */
public final class GuiListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PluginGui gui)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getView().getTopInventory())) {
            gui.onClick(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PluginGui)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
