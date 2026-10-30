package dev.materione.plugin.gui;

import java.util.Collection;
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
 * handlers see an already-cancelled event, on the viewer's region thread). A click outside the window
 * is cancelled and offered to the gui as an outside click.
 * The viewer's own inventory stays live — the browser hands items onto the
 * cursor, and depositing them must work — except the two actions that
 * would move viewer items into the gui or hoover gui display items out:
 * shift-clicks and double-click collection. Drags are cancelled when they
 * touch any gui slot. The decisions live in {@link #route} and
 * {@link #dragCancelled} so the scoping is testable without a server.
 */
public final class GuiListener implements Listener {

    /** Where a click landed, relative to the gui's top inventory. */
    enum Target {
        /** Outside the window: nothing the gui wants to allow. */
        OUTSIDE_WINDOW,
        /** One of the gui's own slots. */
        GUI_TOP,
        /** The viewer's own inventory, shown beside the gui. */
        VIEWER
    }

    /** What the router does with one click. */
    enum Route {
        /** Cancel, then hand the (cancelled) click to the gui's handler. */
        CANCEL_AND_DELIVER,
        /** Cancel it; the gui does not see it. */
        CANCEL,
        /** Leave it alone. */
        PASS
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PluginGui gui)) {
            return;
        }
        Target target;
        if (event.getClickedInventory() == null) {
            target = Target.OUTSIDE_WINDOW;
        } else if (event.getClickedInventory().equals(event.getView().getTopInventory())) {
            target = Target.GUI_TOP;
        } else {
            target = Target.VIEWER;
        }
        switch (route(target, event.getAction())) {
            case CANCEL_AND_DELIVER -> {
                event.setCancelled(true);
                gui.onClick(event);
            }
            case CANCEL -> {
                event.setCancelled(true);
                if (target == Target.OUTSIDE_WINDOW) {
                    gui.onOutsideClick(event); // the click is cancelled; the gui may still react to it
                }
            }
            case PASS -> {
                // the viewer's own inventory keeps working
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PluginGui)) {
            return;
        }
        if (dragCancelled(event.getView().getTopInventory().getSize(), event.getRawSlots())) {
            event.setCancelled(true);
        }
    }

    /**
     * The router's decision for one click inside a gui window (gui.md §5): every click on the
     * gui's own slots or outside the window is cancelled; in the viewer's inventory only the
     * two actions that move items into or out of the gui are cancelled.
     */
    static Route route(Target target, InventoryAction action) {
        return switch (target) {
            case OUTSIDE_WINDOW -> Route.CANCEL;
            case GUI_TOP -> Route.CANCEL_AND_DELIVER;
            case VIEWER -> action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                    || action == InventoryAction.COLLECT_TO_CURSOR
                    ? Route.CANCEL
                    : Route.PASS;
        };
    }

    /** A drag is cancelled when any of its raw slots lies inside the gui's top inventory. */
    static boolean dragCancelled(int topSize, Collection<Integer> rawSlots) {
        for (int rawSlot : rawSlots) {
            if (rawSlot < topSize) {
                return true;
            }
        }
        return false;
    }
}
