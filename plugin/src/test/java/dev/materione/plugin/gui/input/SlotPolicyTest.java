package dev.materione.plugin.gui.input;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

/**
 * The predicted change of each single-slot click on an input slot (logic.md §25). The invariant every case
 * keeps: an item that enters the slot is an item that left the cursor or the hotbar, so the slot never gains
 * an item the player did not hold.
 */
class SlotPolicyTest {

    private static final StackView DIAMONDS_IN_SLOT = new StackView("minecraft:diamond", 10, 64);
    private static final StackView EMPTY_SLOT = StackView.EMPTY;
    private static final StackView CURSOR_RUBIES = new StackView("corpus:ruby", 20, 64);
    private static final StackView CURSOR_DIAMONDS = new StackView("minecraft:diamond", 5, 64);
    private static final StackView HOTBAR_EMERALDS = new StackView("minecraft:emerald", 3, 64);

    @Test
    void aPickupEmptiesTheSlotOrLeavesTheRest() {
        var all = SlotPolicy.change(InventoryAction.PICKUP_ALL, -1, DIAMONDS_IN_SLOT, StackView.EMPTY, StackView.EMPTY);
        assertThat(all.afterAmount()).isZero();
        assertThat(all.entering()).isNull();

        var half = SlotPolicy.change(InventoryAction.PICKUP_HALF, -1, DIAMONDS_IN_SLOT, StackView.EMPTY, StackView.EMPTY);
        assertThat(half.afterAmount()).as("the player takes the rounded-up half, so the slot keeps the floor").isEqualTo(5);

        var one = SlotPolicy.change(InventoryAction.PICKUP_ONE, -1, DIAMONDS_IN_SLOT, StackView.EMPTY, StackView.EMPTY);
        assertThat(one.afterAmount()).isEqualTo(9);
    }

    @Test
    void aPickupNeverLeavesMoreThanTheSlotHeld() {
        for (int held = 1; held <= 64; held++) {
            StackView slot = new StackView("minecraft:diamond", held, 64);
            for (InventoryAction action : new InventoryAction[] {
                    InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE,
                    InventoryAction.PICKUP_SOME}) {
                var change = SlotPolicy.change(action, -1, slot, StackView.EMPTY, StackView.EMPTY);
                assertThat(change.afterAmount()).as(action + " from " + held).isBetween(0, held);
                assertThat(change.entering()).as("a removal adds nothing").isNull();
            }
        }
    }

    @Test
    void aPlacementAddsOnlyWhatTheCursorHoldsAndWhatTheStackHasRoomFor() {
        var fill = SlotPolicy.change(InventoryAction.PLACE_ALL, -1, DIAMONDS_IN_SLOT, CURSOR_DIAMONDS, StackView.EMPTY);
        assertThat(fill.entering()).as("the item that enters is the cursor's").isNotNull();
        assertThat(fill.entering().id()).isEqualTo("minecraft:diamond");
        assertThat(fill.afterAmount()).isEqualTo(15);

        var one = SlotPolicy.change(InventoryAction.PLACE_ONE, -1, EMPTY_SLOT, CURSOR_RUBIES, StackView.EMPTY);
        assertThat(one.entering().amount()).isEqualTo(1);
        assertThat(one.afterAmount()).isEqualTo(1);
        assertThat(one.afterId()).isEqualTo("corpus:ruby");
    }

    @Test
    void aPlacementOntoADifferentItemIsNoChange() {
        // the server would swap these, which is a pickup and a place together: the slot keeps what it holds
        var change = SlotPolicy.change(InventoryAction.PLACE_ALL, -1, DIAMONDS_IN_SLOT, CURSOR_RUBIES, StackView.EMPTY);
        assertThat(change.entering()).isNull();
        assertThat(change.afterAmount()).isEqualTo(10);
    }

    @Test
    void aSwapTakesTheCursorItemInAndTheSlotItemOut() {
        var change = SlotPolicy.change(InventoryAction.SWAP_WITH_CURSOR, -1, DIAMONDS_IN_SLOT, CURSOR_RUBIES, StackView.EMPTY);
        assertThat(change.entering()).isEqualTo(CURSOR_RUBIES);
        assertThat(change.afterId()).isEqualTo("corpus:ruby");
        assertThat(change.afterAmount()).isEqualTo(20);
    }

    @Test
    void aNumberKeySwapTakesTheHotbarItemIn() {
        var change = SlotPolicy.change(InventoryAction.HOTBAR_SWAP, 2, DIAMONDS_IN_SLOT, StackView.EMPTY, HOTBAR_EMERALDS);
        assertThat(change.entering()).isEqualTo(HOTBAR_EMERALDS);
        assertThat(change.afterId()).isEqualTo("minecraft:emerald");
    }

    @Test
    void anOffhandSwapWithAnEmptyOffhandRemovesTheItem() {
        var change = SlotPolicy.change(InventoryAction.HOTBAR_SWAP, SlotPolicy.OFFHAND_BUTTON, DIAMONDS_IN_SLOT,
                StackView.EMPTY, StackView.EMPTY);
        assertThat(change.entering()).as("nothing enters: the offhand was empty").isNull();
        assertThat(change.afterAmount()).isZero();
    }

    @Test
    void dropKeysAndUnknownActionsHaveNoChange() {
        for (InventoryAction action : new InventoryAction[] {
                InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT, InventoryAction.CLONE_STACK,
                InventoryAction.UNKNOWN}) {
            assertThat(SlotPolicy.change(action, -1, DIAMONDS_IN_SLOT, StackView.EMPTY, StackView.EMPTY))
                    .as(action.name()).isNull();
        }
    }

    @Test
    void theRemainderIsBlankWhenTheSlotIsEmpty() {
        var change = new SlotPolicy.Change(null, 0, "minecraft:diamond");
        assertThat(change.afterId()).as("an empty slot holds no identity").isEmpty();
    }
}
