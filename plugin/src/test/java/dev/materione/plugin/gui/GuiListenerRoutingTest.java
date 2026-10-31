package dev.materione.plugin.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.gui.GuiListener.DragRoute;
import dev.materione.plugin.gui.GuiListener.Route;
import dev.materione.plugin.gui.input.ClickTarget;
import java.util.List;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

/**
 * The click policy table (gui.md §5, logic.md §6.5): every click on a gui's own slots is cancelled, the
 * viewer's own inventory keeps working except for the actions that would move items into or out of a gui
 * that does not own them, and the input-slot rows check or move the items the player puts in.
 */
class GuiListenerRoutingTest {

    /** The viewer's inventory is shown beside a gui with no input slots and free. */
    private static final ClickTarget VIEWER = ClickTarget.VIEWER;

    @Test
    void everyClickOnTheGuiItselfIsCancelledAndDelivered() {
        for (InventoryAction action : InventoryAction.values()) {
            assertThat(GuiListener.route(ClickTarget.GUI_TOP, action)).isEqualTo(Route.CANCEL_AND_DELIVER);
        }
    }

    @Test
    void aClickOutsideTheWindowIsCancelled() {
        assertThat(GuiListener.route(ClickTarget.OUTSIDE_WINDOW, InventoryAction.PICKUP_ALL))
                .isEqualTo(Route.CANCEL);
    }

    @Test
    void theViewersOwnInventoryStaysLiveForPickingAndDepositing() {
        for (InventoryAction action : List.of(
                InventoryAction.PICKUP_ALL, InventoryAction.PLACE_ALL, InventoryAction.SWAP_WITH_CURSOR,
                InventoryAction.HOTBAR_SWAP, InventoryAction.DROP_ALL_SLOT)) {
            assertThat(GuiListener.route(VIEWER, action)).as(action.name()).isEqualTo(Route.PASS);
        }
    }

    @Test
    void shiftMovesAndDoubleClickCollectionAreCancelledInTheViewersInventoryWithoutInputSlots() {
        assertThat(GuiListener.route(VIEWER, InventoryAction.MOVE_TO_OTHER_INVENTORY)).isEqualTo(Route.CANCEL);
        assertThat(GuiListener.route(VIEWER, InventoryAction.COLLECT_TO_CURSOR)).isEqualTo(Route.CANCEL);
    }

    @Test
    void aLockedViewerInventoryCancelsEveryClick() {
        for (InventoryAction action : InventoryAction.values()) {
            assertThat(GuiListener.route(ClickTarget.VIEWER_LOCKED, action)).as(action.name()).isEqualTo(Route.CANCEL);
        }
    }

    @Test
    void shiftIntoAGuiWithInputSlotsMovesOnlyIntoThoseSlots() {
        // never the server's own shift-click, which would also fill the gui's display slots
        assertThat(GuiListener.route(ClickTarget.VIEWER_INPUTS, InventoryAction.MOVE_TO_OTHER_INVENTORY))
                .isEqualTo(Route.MOVE_IN);
        assertThat(GuiListener.route(ClickTarget.VIEWER_INPUTS, InventoryAction.COLLECT_TO_CURSOR))
                .isEqualTo(Route.COLLECT);
        assertThat(GuiListener.route(ClickTarget.VIEWER_INPUTS, InventoryAction.PICKUP_ALL)).isEqualTo(Route.PASS);
    }

    @Test
    void theInputSlotActionsAreCheckedAndTheMovesAreApplied() {
        for (InventoryAction action : List.of(
                InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE,
                InventoryAction.PICKUP_SOME, InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE,
                InventoryAction.PLACE_SOME, InventoryAction.SWAP_WITH_CURSOR, InventoryAction.HOTBAR_SWAP)) {
            assertThat(GuiListener.route(ClickTarget.INPUT_SLOT, action)).as(action.name()).isEqualTo(Route.CHECKED);
        }
        assertThat(GuiListener.route(ClickTarget.INPUT_SLOT, InventoryAction.MOVE_TO_OTHER_INVENTORY))
                .isEqualTo(Route.MOVE_OUT);
        assertThat(GuiListener.route(ClickTarget.INPUT_SLOT, InventoryAction.COLLECT_TO_CURSOR))
                .isEqualTo(Route.COLLECT);
    }

    @Test
    void dropKeysAndTheCreativeCloneAreRefusedInAnInputSlot() {
        // a drop or a clone takes an item out of the window with no check, so none of them may happen there
        for (InventoryAction action : List.of(
                InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT, InventoryAction.CLONE_STACK,
                InventoryAction.UNKNOWN)) {
            assertThat(GuiListener.route(ClickTarget.INPUT_SLOT, action)).as(action.name()).isEqualTo(Route.CANCEL);
        }
    }

    @Test
    void aDragIsCancelledOnlyWhenItTouchesAGuiSlot() {
        // a top inventory of 27 slots: raw slots 0..26 are the gui, 27.. are the viewer's
        assertThat(GuiListener.dragCancelled(27, List.of(3, 30))).isTrue();
        assertThat(GuiListener.dragCancelled(27, List.of(27, 40))).isFalse();
        assertThat(GuiListener.dragCancelled(27, List.of())).isFalse();
    }

    @Test
    void aDragOverInputSlotsOnlyIsChecked() {
        var inputs = java.util.Set.of(9, 10);
        assertThat(GuiListener.dragRoute(27, List.of(9, 10), inputs::contains, false)).isEqualTo(DragRoute.CHECKED);
        assertThat(GuiListener.dragRoute(27, List.of(9, 10, 30), inputs::contains, false))
                .as("the viewer's slots may join an input drag").isEqualTo(DragRoute.CHECKED);
    }

    @Test
    void aDragThatTouchesAGuiSlotWhichIsNotAnInputSlotIsCancelledEvenWithInputsAround() {
        var inputs = java.util.Set.of(9, 10);
        // a display slot (3) under the drag would take the items: the whole drag is refused
        assertThat(GuiListener.dragRoute(27, List.of(9, 3), inputs::contains, false)).isEqualTo(DragRoute.CANCEL);
        assertThat(GuiListener.dragRoute(27, List.of(9, 30), inputs::contains, true))
                .as("a locked viewer inventory refuses a drag that reaches it").isEqualTo(DragRoute.CANCEL);
        assertThat(GuiListener.dragRoute(27, List.of(30, 31), inputs::contains, false)).isEqualTo(DragRoute.PASS);
    }
}
