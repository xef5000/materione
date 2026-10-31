package dev.materione.plugin.gui;

import dev.materione.plugin.gui.input.ClickTarget;
import dev.materione.plugin.gui.input.InputSlotClicks;
import dev.materione.plugin.gui.input.WindowPolicy;
import java.util.Collection;
import java.util.function.IntPredicate;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * The single click router for every {@link PluginGui} (registered once, static wiring like the other
 * listeners). Identification is holder identity: a gui is whatever {@code InventoryHolder} the top
 * inventory was created with, so renames, colors and locales can never break routing.
 *
 * <p>This class is an adapter with one table. It reads the click, decides its {@link ClickTarget} from the
 * window's {@link WindowPolicy}, looks the target and action up in {@link #route}, and does what the route
 * says: cancel, deliver the click to the gui, or hand it to {@link InputSlotClicks}, which holds the input
 * rules (logic.md §25). It holds no rule of its own.
 *
 * <p>Clicks in the gui itself are cancelled and handed to the gui (its handlers see an already-cancelled
 * event, on the viewer's region thread). The viewer's own inventory stays live (the browser hands items onto
 * the cursor, and depositing them must work) except the actions that would move items the gui does not
 * own: a shift-click or double-click collection into a gui with no input slots, and every click while the
 * viewer's inventory is locked. Input slots take the player's items through the checked, planned moves of
 * {@link InputSlotClicks}. Drags are cancelled when they touch a gui slot that is not an input slot.
 */
public final class GuiListener implements Listener {

    /** What the router does with one click. */
    enum Route {
        /** Cancel, then hand the (cancelled) click to the gui's handler. */
        CANCEL_AND_DELIVER,
        /** Cancel it; the gui does not see it. */
        CANCEL,
        /** Leave it alone. */
        PASS,
        /** An input slot's single-slot click: the server applies it when the checks pass, else it is cancelled. */
        CHECKED,
        /** A shift-click from the viewer into the input slots: cancelled, and applied by the input rules. */
        MOVE_IN,
        /** A shift-click out of an input slot into the viewer's inventory: cancelled, applied by the input rules. */
        MOVE_OUT,
        /** A double-click collection onto the cursor from the input slots and the viewer: applied by the input rules. */
        COLLECT
    }

    /** What a drag does: cancelled, passed to the server after the input checks, or left alone. */
    enum DragRoute {
        CANCEL,
        CHECKED,
        PASS
    }

    private final InputSlotClicks inputs;

    /**
     * @param inputs the input-slot rules; null on a host without a GUI runtime, where every window is judged
     *     as one with no input slots (the behaviour before #218)
     */
    public GuiListener(InputSlotClicks inputs) {
        this.inputs = inputs;
    }

    /** The window's policy; a window of the older GUIs, or any window with no input rules, is {@link WindowPolicy#LEGACY}. */
    private WindowPolicy policyOf(PluginGui gui, org.bukkit.entity.HumanEntity who) {
        return inputs == null ? WindowPolicy.LEGACY : inputs.policyOf(gui, who);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PluginGui gui)) {
            return;
        }
        WindowPolicy policy = policyOf(gui, event.getWhoClicked());
        boolean outside = event.getClickedInventory() == null;
        boolean top = !outside && event.getClickedInventory().equals(event.getView().getTopInventory());
        ClickTarget target = ClickTarget.of(policy, outside, top, event.getSlot());
        if (target.viewer() && policy.live() && inputs.notifyInventory(gui, event)) {
            event.setCancelled(true); // gui.click_inventory: a rule refused the click in the viewer's inventory
            return;
        }
        switch (route(target, event.getAction())) {
            case CANCEL_AND_DELIVER -> {
                event.setCancelled(true);
                gui.onClick(event);
            }
            case CANCEL -> {
                event.setCancelled(true);
                if (target == ClickTarget.OUTSIDE_WINDOW) {
                    gui.onOutsideClick(event); // the click is cancelled; the gui may still react to it
                }
            }
            case PASS -> {
                // the viewer's own inventory keeps working
            }
            case CHECKED -> {
                if (inputs.checked((GuiMenu) gui, event)) {
                    gui.onClick(event); // the server applies the click; the gui sees it for gui.click
                } else {
                    event.setCancelled(true);
                }
            }
            case MOVE_IN -> {
                event.setCancelled(true);
                inputs.shiftIn((GuiMenu) gui, event);
            }
            case MOVE_OUT -> {
                event.setCancelled(true);
                inputs.shiftOut((GuiMenu) gui, event);
            }
            case COLLECT -> {
                event.setCancelled(true);
                inputs.collect((GuiMenu) gui, event);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PluginGui gui)) {
            return;
        }
        WindowPolicy policy = policyOf(gui, event.getWhoClicked());
        int topSize = event.getView().getTopInventory().getSize();
        IntPredicate input = slot -> policy.live() && policy.slots().isInput(slot);
        // a stale window of a session locks the viewer's inventory too, as its clicks do
        boolean locked = policy.menu() && (!policy.live() || policy.slots().locked());
        switch (dragRoute(topSize, event.getRawSlots(), input, locked)) {
            case CANCEL -> event.setCancelled(true);
            case CHECKED -> {
                if (!inputs.drag((GuiMenu) gui, event)) {
                    event.setCancelled(true);
                }
            }
            case PASS -> {
                // the drag stays with the viewer
            }
        }
    }

    /**
     * The policy table for a click (gui.md §5, logic.md §6.5). Every click on a gui's own slot is cancelled
     * and delivered, except an input slot's, which is checked (the single-slot actions), or moved by the input
     * rules (shift-click out, double-click collection). The viewer's inventory: free, a shift-click or
     * double-click collection is cancelled unless the gui has input slots, in which case the input rules
     * move items; locked, every click is cancelled.
     */
    static Route route(ClickTarget target, InventoryAction action) {
        return switch (target) {
            case OUTSIDE_WINDOW -> Route.CANCEL;
            case GUI_TOP -> Route.CANCEL_AND_DELIVER;
            case VIEWER_LOCKED -> Route.CANCEL;
            case VIEWER -> action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                    || action == InventoryAction.COLLECT_TO_CURSOR
                    ? Route.CANCEL
                    : Route.PASS;
            case VIEWER_INPUTS -> switch (action) {
                case MOVE_TO_OTHER_INVENTORY -> Route.MOVE_IN;
                case COLLECT_TO_CURSOR -> Route.COLLECT;
                default -> Route.PASS;
            };
            case INPUT_SLOT -> switch (action) {
                case MOVE_TO_OTHER_INVENTORY -> Route.MOVE_OUT;
                case COLLECT_TO_CURSOR -> Route.COLLECT;
                case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, PLACE_ALL, PLACE_ONE, PLACE_SOME,
                        SWAP_WITH_CURSOR, HOTBAR_SWAP -> Route.CHECKED;
                // a drop key, the creative clone and unknown actions take an item out of the window
                // with no check, so they are refused
                default -> Route.CANCEL;
            };
        };
    }

    /**
     * The policy table for a drag (logic.md §6.5): a drag that touches a gui slot which is not an input slot
     * is cancelled; a drag that touches only input slots is checked; a locked viewer inventory cancels a drag
     * that touches it; a drag that stays in the viewer's inventory is left alone.
     *
     * @param topSize the number of slots in the gui's top inventory: raw slots below it are the gui's
     * @param input whether a raw slot of the gui is an input slot
     * @param locked whether the viewer's inventory is locked
     */
    static DragRoute dragRoute(int topSize, Collection<Integer> rawSlots, IntPredicate input, boolean locked) {
        boolean touchesTop = false;
        boolean touchesViewer = false;
        for (int rawSlot : rawSlots) {
            if (rawSlot < topSize) {
                touchesTop = true;
                if (!input.test(rawSlot)) {
                    return DragRoute.CANCEL;
                }
            } else {
                touchesViewer = true;
            }
        }
        if (locked && touchesViewer) {
            return DragRoute.CANCEL;
        }
        return touchesTop ? DragRoute.CHECKED : DragRoute.PASS;
    }

    /** A drag is cancelled when any of its raw slots lies inside the gui's top inventory (a gui with no input slots). */
    static boolean dragCancelled(int topSize, Collection<Integer> rawSlots) {
        return dragRoute(topSize, rawSlots, slot -> false, false) == DragRoute.CANCEL;
    }
}
