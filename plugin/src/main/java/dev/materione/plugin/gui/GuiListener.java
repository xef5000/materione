package dev.materione.plugin.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * The single click router for every {@link PluginGui} (registered once,
 * static wiring like the other listeners). Identification is holder
 * identity — a gui is whatever {@code InventoryHolder} the top inventory
 * was created with — so renames, colors and locales can never break
 * routing.
 *
 * <p>Clicks in the gui itself are cancelled and handed to the gui (its
 * handlers see an already-cancelled event, on the viewer's region thread).
 * The viewer's own inventory stays live — the browser hands items onto the
 * cursor, and depositing them must work — except the two actions that
 * would move viewer items into the gui or hoover gui display items out:
 * shift-clicks and double-click collection. Drags are cancelled when they
 * touch any gui slot.
 */
public final class GuiListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PluginGui gui)) {
            return;
        }
        if (event.getClickedInventory() == null) {
            event.setCancelled(true); // clicked outside the window: nothing the gui wants to allow
            return;
        }
        if (event.getClickedInventory().equals(event.getView().getTopInventory())) {
            event.setCancelled(true);
            gui.onClick(event);
            return;
        }
        // the viewer's own inventory: depositing a picked item must work
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
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
