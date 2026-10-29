package dev.materione.plugin.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.gui.GuiListener.Route;
import dev.materione.plugin.gui.GuiListener.Target;
import java.util.List;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

/**
 * The click router's scoping (gui.md §5): every click on a gui's own slots is cancelled, and the
 * viewer's own inventory keeps working except for the two actions that would move items into or
 * out of the gui.
 */
class GuiListenerRoutingTest {

    @Test
    void everyClickOnTheGuiItselfIsCancelledAndDelivered() {
        for (InventoryAction action : InventoryAction.values()) {
            assertThat(GuiListener.route(Target.GUI_TOP, action)).isEqualTo(Route.CANCEL_AND_DELIVER);
        }
    }

    @Test
    void aClickOutsideTheWindowIsCancelled() {
        assertThat(GuiListener.route(Target.OUTSIDE_WINDOW, InventoryAction.PICKUP_ALL))
                .isEqualTo(Route.CANCEL);
    }

    @Test
    void theViewersOwnInventoryStaysLiveForPickingAndDepositing() {
        for (InventoryAction action : List.of(
                InventoryAction.PICKUP_ALL, InventoryAction.PLACE_ALL, InventoryAction.SWAP_WITH_CURSOR,
                InventoryAction.HOTBAR_SWAP, InventoryAction.DROP_ALL_SLOT)) {
            assertThat(GuiListener.route(Target.VIEWER, action)).as(action.name()).isEqualTo(Route.PASS);
        }
    }

    @Test
    void shiftMovesAndDoubleClickCollectionAreCancelledInTheViewersInventory() {
        assertThat(GuiListener.route(Target.VIEWER, InventoryAction.MOVE_TO_OTHER_INVENTORY))
                .isEqualTo(Route.CANCEL);
        assertThat(GuiListener.route(Target.VIEWER, InventoryAction.COLLECT_TO_CURSOR))
                .isEqualTo(Route.CANCEL);
    }

    @Test
    void aDragIsCancelledOnlyWhenItTouchesAGuiSlot() {
        // a top inventory of 27 slots: raw slots 0..26 are the gui, 27.. are the viewer's
        assertThat(GuiListener.dragCancelled(27, List.of(3, 30))).isTrue();
        assertThat(GuiListener.dragCancelled(27, List.of(27, 40))).isFalse();
        assertThat(GuiListener.dragCancelled(27, List.of())).isFalse();
    }
}
