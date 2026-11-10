package dev.materione.plugin.escrow.runtime;

import static dev.materione.plugin.escrow.runtime.Rig.item;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.escrow.runtime.ClickPlanner.Outcome;
import dev.materione.plugin.escrow.runtime.Rig.Item;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

/** What each single-slot action does to the slot, the cursor and the hotbar slot (gui-input-durability.md §5.5). */
class ClickPlannerTest {

    private final ClickPlanner<Item> planner = new ClickPlanner<>(new Rig.FakeStacks());

    private Outcome<Item> plan(InventoryAction action, Item slot, Item cursor, Item other) {
        return planner.plan(action, slot, cursor, other).orElseThrow();
    }

    @Test
    void aLeftPickupLiftsTheWholeStackToTheCursor() {
        Outcome<Item> out = plan(InventoryAction.PICKUP_ALL, item("gem", 7), null, null);
        assertThat(out.slot()).isNull();
        assertThat(out.cursor()).isEqualTo(item("gem", 7));
    }

    @Test
    void aRightPickupLiftsTheLargerHalf() {
        Outcome<Item> out = plan(InventoryAction.PICKUP_HALF, item("gem", 7), null, null);
        assertThat(out.slot()).isEqualTo(item("gem", 3));
        assertThat(out.cursor()).isEqualTo(item("gem", 4));
        // a single item: the slot empties
        Outcome<Item> one = plan(InventoryAction.PICKUP_HALF, item("gem", 1), null, null);
        assertThat(one.slot()).isNull();
        assertThat(one.cursor()).isEqualTo(item("gem", 1));
    }

    @Test
    void pickupOneTakesOne() {
        Outcome<Item> out = plan(InventoryAction.PICKUP_ONE, item("gem", 5), null, null);
        assertThat(out.slot()).isEqualTo(item("gem", 4));
        assertThat(out.cursor()).isEqualTo(item("gem", 1));
    }

    @Test
    void pickupSomeFillsTheCursorToItsRoom() {
        Outcome<Item> out = plan(InventoryAction.PICKUP_SOME, item("gem", 40), item("gem", 60), null);
        assertThat(out.slot()).isEqualTo(item("gem", 36));
        assertThat(out.cursor()).isEqualTo(item("gem", 64));
    }

    @Test
    void placingAllIntoAnEmptySlotEmptiesTheCursor() {
        Outcome<Item> out = plan(InventoryAction.PLACE_ALL, null, item("gem", 5), null);
        assertThat(out.slot()).isEqualTo(item("gem", 5));
        assertThat(out.cursor()).isNull();
    }

    @Test
    void placingMergesIntoASimilarStackAndKeepsTheRemainderOnTheCursor() {
        Outcome<Item> out = plan(InventoryAction.PLACE_SOME, item("gem", 60), item("gem", 10), null);
        assertThat(out.slot()).isEqualTo(item("gem", 64));
        assertThat(out.cursor()).isEqualTo(item("gem", 6));
    }

    @Test
    void placingOneMovesOneItem() {
        Outcome<Item> out = plan(InventoryAction.PLACE_ONE, item("gem", 2), item("gem", 5), null);
        assertThat(out.slot()).isEqualTo(item("gem", 3));
        assertThat(out.cursor()).isEqualTo(item("gem", 4));
        assertThat(plan(InventoryAction.PLACE_ONE, null, item("gem", 1), null).cursor()).isNull();
    }

    @Test
    void anItemThatStacksToOneFillsOnlyAnEmptySlot() {
        assertThat(planner.plan(InventoryAction.PLACE_ONE, item("sword", 1), item("sword", 1), null)).isEmpty();
        assertThat(plan(InventoryAction.PLACE_ALL, null, item("sword", 1), null).slot()).isEqualTo(item("sword", 1));
    }

    @Test
    void aSwapExchangesTheSlotAndTheCursor() {
        Outcome<Item> out = plan(InventoryAction.SWAP_WITH_CURSOR, item("gem", 3), item("coal", 9), null);
        assertThat(out.slot()).isEqualTo(item("coal", 9));
        assertThat(out.cursor()).isEqualTo(item("gem", 3));
    }

    @Test
    void aHotbarKeySwapsTheSlotWithTheHotbarSlotWhicheverIsEmpty() {
        Outcome<Item> both = plan(InventoryAction.HOTBAR_SWAP, item("gem", 3), null, item("coal", 9));
        assertThat(both.slot()).isEqualTo(item("coal", 9));
        assertThat(both.other()).isEqualTo(item("gem", 3));
        Outcome<Item> toHotbar = plan(InventoryAction.HOTBAR_SWAP, item("gem", 3), null, null);
        assertThat(toHotbar.slot()).isNull();
        assertThat(toHotbar.other()).isEqualTo(item("gem", 3));
        Outcome<Item> fromHotbar = plan(InventoryAction.HOTBAR_SWAP, null, null, item("coal", 9));
        assertThat(fromHotbar.slot()).isEqualTo(item("coal", 9));
        assertThat(fromHotbar.other()).isNull();
        // the cursor is not touched by a hotbar swap
        assertThat(plan(InventoryAction.HOTBAR_SWAP, item("gem", 1), item("x", 2), null).cursor()).isEqualTo(item("x", 2));
    }

    @Test
    void anActionThatContradictsTheStateHasNoPlan() {
        assertThat(planner.plan(InventoryAction.PICKUP_ALL, item("gem", 1), item("x", 1), null)).isEmpty();
        assertThat(planner.plan(InventoryAction.PICKUP_ALL, null, null, null)).isEmpty();
        assertThat(planner.plan(InventoryAction.PLACE_ALL, item("gem", 1), item("coal", 1), null)).isEmpty();
        assertThat(planner.plan(InventoryAction.SWAP_WITH_CURSOR, item("gem", 1), null, null)).isEmpty();
        assertThat(planner.plan(InventoryAction.HOTBAR_SWAP, null, null, null)).isEmpty();
    }

    @Test
    void theActionsThatAreNotASlotsOwnChangeHaveNoPlan() {
        for (InventoryAction action : new InventoryAction[] {InventoryAction.DROP_ALL_SLOT,
                InventoryAction.DROP_ONE_SLOT, InventoryAction.CLONE_STACK, InventoryAction.UNKNOWN,
                InventoryAction.MOVE_TO_OTHER_INVENTORY, InventoryAction.COLLECT_TO_CURSOR,
                InventoryAction.NOTHING}) {
            assertThat(planner.plan(action, item("gem", 1), null, null)).as(action.name()).isEmpty();
        }
    }
}
