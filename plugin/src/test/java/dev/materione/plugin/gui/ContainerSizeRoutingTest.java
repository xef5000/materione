package dev.materione.plugin.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.gui.GuiListener.DragRoute;
import dev.materione.plugin.gui.GuiListener.Route;
import dev.materione.plugin.gui.input.ClickTarget;
import dev.materione.plugin.gui.input.InputSlots;
import dev.materione.plugin.gui.input.WindowPolicy;
import java.util.List;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

/**
 * The click router is size-agnostic (gui.md §12.6): the same table judges a click on a hopper's five-slot top and on
 * a dispenser's nine-slot top, and the only per-container facts are the top size and the slot ids. Pure JVM: the
 * router takes the policy, the top size and the raw slots; the holder check is the listener's own (its test is the
 * product run in the harness).
 */
class ContainerSizeRoutingTest {

    /** A hopper's top: five slots, raw 0..4; the viewer's inventory is raw 5..40. */
    private static final int HOPPER_TOP = 5;
    /** A dispenser's top: nine slots, raw 0..8; the viewer's inventory is raw 9..44. */
    private static final int DISPENSER_TOP = 9;

    /** Slot 0 and 1 of a hopper, slot 8 of a dispenser, as input slots (logic.md §25). */
    private static final InputSlots HOPPER_INPUTS = InputSlots.of(List.of(
            new InputSlots.Input(0, "wells", null, false),
            new InputSlots.Input(1, "wells", null, false)), false);
    private static final InputSlots DISPENSER_INPUTS = InputSlots.of(List.of(
            new InputSlots.Input(8, "wells", null, false)), false);

    @Test
    void theClickTargetOfAFiveSlotTopIsDecidedBySlotIdNotByContainerSize() {
        WindowPolicy hopper = WindowPolicy.live(HOPPER_INPUTS);

        assertThat(ClickTarget.of(hopper, false, true, 0)).isEqualTo(ClickTarget.INPUT_SLOT);
        assertThat(ClickTarget.of(hopper, false, true, 4)).isEqualTo(ClickTarget.GUI_TOP);
        assertThat(ClickTarget.of(hopper, false, false, 12)).isEqualTo(ClickTarget.VIEWER_INPUTS);
        assertThat(ClickTarget.of(hopper, true, false, -999)).isEqualTo(ClickTarget.OUTSIDE_WINDOW);
    }

    @Test
    void theClickTargetOfANineSlotTopIsDecidedBySlotIdNotByContainerSize() {
        WindowPolicy dispenser = WindowPolicy.live(DISPENSER_INPUTS);

        assertThat(ClickTarget.of(dispenser, false, true, 8)).isEqualTo(ClickTarget.INPUT_SLOT);
        assertThat(ClickTarget.of(dispenser, false, true, 4)).isEqualTo(ClickTarget.GUI_TOP);
        assertThat(ClickTarget.of(dispenser, false, false, 30)).isEqualTo(ClickTarget.VIEWER_INPUTS);
        assertThat(ClickTarget.of(dispenser, true, false, -999)).isEqualTo(ClickTarget.OUTSIDE_WINDOW);
    }

    @Test
    void theRouteTableHasNoSizeInItSoEveryActionOnAGuiSlotOrOutsideIsTheSameOnBothTops() {
        // the table takes a target and an action only: a hopper's display slot and a dispenser's get one route
        for (InventoryAction action : InventoryAction.values()) {
            assertThat(GuiListener.route(ClickTarget.of(WindowPolicy.live(HOPPER_INPUTS), false, true, 4), action))
                    .as("hopper display slot, " + action).isEqualTo(GuiListener.route(ClickTarget.GUI_TOP, action));
            assertThat(GuiListener.route(ClickTarget.of(WindowPolicy.live(DISPENSER_INPUTS), false, true, 4), action))
                    .as("dispenser display slot, " + action).isEqualTo(GuiListener.route(ClickTarget.GUI_TOP, action));
            assertThat(GuiListener.route(ClickTarget.of(WindowPolicy.live(HOPPER_INPUTS), true, false, -999), action))
                    .as("hopper outside, " + action).isEqualTo(Route.CANCEL);
            assertThat(GuiListener.route(ClickTarget.of(WindowPolicy.live(DISPENSER_INPUTS), true, false, -999), action))
                    .as("dispenser outside, " + action).isEqualTo(Route.CANCEL);
        }
    }

    @Test
    void aShiftClickFromAHopperOrADispenserIsMovedOnlyIntoItsInputSlots() {
        // the input slot of each top is its own: slot 1 of the hopper's, slot 8 of the dispenser's (the last one)
        assertThat(GuiListener.route(ClickTarget.of(WindowPolicy.live(HOPPER_INPUTS), false, false, 30),
                InventoryAction.MOVE_TO_OTHER_INVENTORY)).isEqualTo(Route.MOVE_IN);
        assertThat(GuiListener.route(ClickTarget.of(WindowPolicy.live(HOPPER_INPUTS), false, true, 1),
                InventoryAction.MOVE_TO_OTHER_INVENTORY)).isEqualTo(Route.MOVE_OUT);
        assertThat(GuiListener.route(ClickTarget.of(WindowPolicy.live(DISPENSER_INPUTS), false, false, 30),
                InventoryAction.MOVE_TO_OTHER_INVENTORY)).isEqualTo(Route.MOVE_IN);
        assertThat(GuiListener.route(ClickTarget.of(WindowPolicy.live(DISPENSER_INPUTS), false, true, 8),
                InventoryAction.MOVE_TO_OTHER_INVENTORY)).isEqualTo(Route.MOVE_OUT);
        // slot 0 is a display slot on the dispenser: a shift-click from it is not an input move
        assertThat(GuiListener.route(ClickTarget.of(WindowPolicy.live(DISPENSER_INPUTS), false, true, 0),
                InventoryAction.MOVE_TO_OTHER_INVENTORY)).isEqualTo(Route.CANCEL_AND_DELIVER);
    }

    @Test
    void aLockedViewerInventoryIsLockedOnEitherSize() {
        WindowPolicy locked = WindowPolicy.live(InputSlots.of(List.of(), true));

        assertThat(ClickTarget.of(locked, false, false, 12)).isEqualTo(ClickTarget.VIEWER_LOCKED);
        assertThat(ClickTarget.of(locked, false, false, 30)).isEqualTo(ClickTarget.VIEWER_LOCKED);
    }

    @Test
    void aDragTouchingOnlyAFiveSlotTopsInputSlotsIsChecked() {
        assertThat(GuiListener.dragRoute(HOPPER_TOP, List.of(0, 1), slot -> slot <= 1, false))
                .isEqualTo(DragRoute.CHECKED);
    }

    @Test
    void aDragTouchingAFiveSlotTopsDisplaySlotIsCancelled() {
        // raw 4 is the hopper's last slot: a display slot here, so the drag is refused whatever else it touches
        assertThat(GuiListener.dragRoute(HOPPER_TOP, List.of(0, 4), slot -> slot <= 1, false))
                .isEqualTo(DragRoute.CANCEL);
    }

    @Test
    void theViewersRawSlotsStartAtTheTopSize() {
        // raw 5 is the viewer's first main slot in a hopper, but a display slot of a dispenser: the top size decides
        assertThat(GuiListener.dragRoute(HOPPER_TOP, List.of(5), slot -> false, false)).isEqualTo(DragRoute.PASS);
        assertThat(GuiListener.dragRoute(DISPENSER_TOP, List.of(5), slot -> false, false)).isEqualTo(DragRoute.CANCEL);
        assertThat(GuiListener.dragRoute(DISPENSER_TOP, List.of(9), slot -> false, false)).isEqualTo(DragRoute.PASS);
    }

    @Test
    void aLockedViewerInventoryCancelsADragOnAFiveSlotTopToo() {
        assertThat(GuiListener.dragRoute(HOPPER_TOP, List.of(0, 6), slot -> slot == 0, true))
                .isEqualTo(DragRoute.CANCEL);
    }

}
