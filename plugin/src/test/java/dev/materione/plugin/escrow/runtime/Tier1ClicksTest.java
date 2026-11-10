package dev.materione.plugin.escrow.runtime;

import static dev.materione.plugin.escrow.runtime.Rig.GUI;
import static dev.materione.plugin.escrow.runtime.Rig.SLOTS_0_TO_8;
import static dev.materione.plugin.escrow.runtime.Rig.item;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.escrow.model.Account;
import dev.materione.plugin.escrow.model.AccountCodec;
import dev.materione.plugin.escrow.model.Entry;
import dev.materione.plugin.escrow.model.EntryState;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.StackBytes;
import dev.materione.plugin.escrow.runtime.Rig.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

/**
 * Every strict click on a {@code durable: required} slot (gui-input-durability.md §5.5): single-slot place, pickup,
 * swap, number key, offhand swap, shift in and out, drag and the double-click collection are one tier-1 step. After each
 * one the account written to the player's data is the live account, every escrowed slot is exactly its entry, and
 * nothing the player owns outside the cursor is created or lost.
 */
class Tier1ClicksTest {

    private final Rig rig = Rig.open();
    private final ClickPlanner<Item> planner = new ClickPlanner<>(rig.stacks);

    /** The step a manual single-slot click performs: plan, then one {@link Tier1#apply}. */
    private Tier1.Result<Item> click(InventoryAction action, int slot, Integer bagIndex) {
        Item other = bagIndex == null ? null : rig.world.bag(bagIndex);
        var outcome = planner.plan(action, rig.world.top(slot), rig.world.cursor(), other).orElseThrow();
        Delta<Item> delta = new Delta<Item>().top(slot, outcome.slot()).cursor(outcome.cursor());
        if (bagIndex != null) {
            delta.bag(bagIndex, outcome.other());
        }
        return rig.tier1.apply(rig.escrow, rig.world, GUI, SLOTS_0_TO_8, delta);
    }

    private void consistent() {
        rig.assertConsistent(Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8));
    }

    @Test
    void placingFromTheCursorCreatesTheEntryInTheSameStep() {
        rig.world.cursor = item("gem", 5);
        assertThat(click(InventoryAction.PLACE_ALL, 2, null)).isInstanceOf(Tier1.Done.class);
        assertThat(rig.world.top(2)).isEqualTo(item("gem", 5));
        assertThat(rig.world.cursor()).isNull();
        assertThat(rig.persisted().entries()).singleElement().satisfies(entry -> {
            assertThat(entry.state()).isEqualTo(EntryState.HELD);
            assertThat(entry.slotHint()).isEqualTo(2);
            assertThat(entry.originGui()).isEqualTo(GUI);
        });
        consistent();
    }

    @Test
    void placingMoreOntoAStackEditsItsEntry() {
        rig.world.cursor = item("gem", 5);
        click(InventoryAction.PLACE_ALL, 2, null);
        rig.world.cursor = item("gem", 4);
        click(InventoryAction.PLACE_ONE, 2, null);
        assertThat(rig.world.top(2)).isEqualTo(item("gem", 6));
        assertThat(rig.world.cursor()).isEqualTo(item("gem", 3));
        assertThat(rig.escrow.account().entries()).hasSize(1);
        consistent();
    }

    @Test
    void aPickupLiftsToTheCursorAndTheAccountLosesItInTheSameStep() {
        rig.world.cursor = item("gem", 5);
        click(InventoryAction.PLACE_ALL, 2, null);
        click(InventoryAction.PICKUP_ALL, 2, null);
        // the cursor is outside the guarantee (G5): the stack is the player's cursor stack and in no account
        assertThat(rig.world.cursor()).isEqualTo(item("gem", 5));
        assertThat(rig.world.top(2)).isNull();
        assertThat(rig.persisted().entries()).isEmpty();
        consistent();
    }

    @Test
    void aHalfPickupLeavesTheOtherHalfEscrowed() {
        rig.world.cursor = item("gem", 7);
        click(InventoryAction.PLACE_ALL, 2, null);
        click(InventoryAction.PICKUP_HALF, 2, null);
        assertThat(rig.world.cursor()).isEqualTo(item("gem", 4));
        assertThat(rig.world.top(2)).isEqualTo(item("gem", 3));
        assertThat(rig.escrow.account().entries()).singleElement()
                .satisfies(entry -> assertThat(rig.stacks.decode(entry.stack())).isEqualTo(item("gem", 3)));
        consistent();
    }

    @Test
    void aSwapWithTheCursorExchangesTheStacksAndTheEntry() {
        rig.world.cursor = item("gem", 5);
        click(InventoryAction.PLACE_ALL, 2, null);
        rig.world.cursor = item("coal", 9);
        click(InventoryAction.SWAP_WITH_CURSOR, 2, null);
        assertThat(rig.world.top(2)).isEqualTo(item("coal", 9));
        assertThat(rig.world.cursor()).isEqualTo(item("gem", 5));
        assertThat(rig.escrow.account().entries()).hasSize(1);
        consistent();
    }

    @Test
    void aNumberKeyMovesBetweenTheSlotAndTheHotbarInsideTheGuarantee() {
        rig.world.bag[3] = item("gem", 8);
        click(InventoryAction.HOTBAR_SWAP, 4, 3); // hotbar item into an empty slot: a move-in
        assertThat(rig.world.top(4)).isEqualTo(item("gem", 8));
        assertThat(rig.world.bag[3]).isNull();
        assertThat(rig.escrow.account().entries()).hasSize(1);
        consistent();

        rig.world.bag[3] = item("coal", 2);
        click(InventoryAction.HOTBAR_SWAP, 4, 3); // both full: swapped
        assertThat(rig.world.top(4)).isEqualTo(item("coal", 2));
        assertThat(rig.world.bag[3]).isEqualTo(item("gem", 8));
        consistent();

        rig.world.bag[3] = null;
        click(InventoryAction.HOTBAR_SWAP, 4, 3); // hotbar empty: a move-out, the stack is in the inventory at once
        assertThat(rig.world.top(4)).isNull();
        assertThat(rig.world.bag[3]).isEqualTo(item("coal", 2));
        assertThat(rig.persisted().entries()).isEmpty();
        consistent();
    }

    @Test
    void theOffhandSwapUsesTheOffhandSlot() {
        rig.world.bag[Live.OFFHAND] = item("shield", 1);
        click(InventoryAction.HOTBAR_SWAP, 0, Live.OFFHAND);
        assertThat(rig.world.top(0)).isEqualTo(item("shield", 1));
        assertThat(rig.world.bag[Live.OFFHAND]).isNull();
        consistent();
    }

    @Test
    void aShiftInSpreadsOverSlotsAndReducesTheSourceInOneStep() {
        rig.world.bag[10] = item("gem", 100);
        Delta<Item> delta = new Delta<Item>().top(0, item("gem", 64)).top(1, item("gem", 36)).bag(10, null);
        assertThat(rig.tier1.apply(rig.escrow, rig.world, GUI, SLOTS_0_TO_8, delta)).isInstanceOf(Tier1.Done.class);
        assertThat(rig.escrow.account().entries()).hasSize(2);
        assertThat(rig.world.bag[10]).isNull();
        consistent();
    }

    @Test
    void aShiftOutMovesToTheInventoryAndEmptiesTheEntry() {
        rig.world.cursor = item("gem", 5);
        click(InventoryAction.PLACE_ALL, 6, null);
        Delta<Item> delta = new Delta<Item>().top(6, null).bag(9, item("gem", 5));
        rig.tier1.apply(rig.escrow, rig.world, GUI, SLOTS_0_TO_8, delta);
        assertThat(rig.escrow.account().isEmpty()).isTrue();
        assertThat(rig.world.bag[9]).isEqualTo(item("gem", 5));
        consistent();
    }

    @Test
    void aDragFillsSeveralSlotsAndShrinksTheCursor() {
        rig.world.cursor = item("gem", 10);
        Delta<Item> delta = new Delta<Item>().top(0, item("gem", 3)).top(1, item("gem", 3)).bag(20, item("gem", 3))
                .cursor(item("gem", 1));
        rig.tier1.apply(rig.escrow, rig.world, GUI, SLOTS_0_TO_8, delta);
        assertThat(rig.escrow.account().entries()).hasSize(2);
        assertThat(rig.world.bag[20]).isEqualTo(item("gem", 3));
        assertThat(rig.world.cursor()).isEqualTo(item("gem", 1));
        consistent();
    }

    @Test
    void aDoubleClickCollectionTakesFromSlotsAndInventoryOntoTheCursor() {
        rig.world.cursor = item("gem", 5);
        click(InventoryAction.PLACE_ALL, 0, null);
        rig.world.cursor = item("gem", 1);
        rig.world.bag[12] = item("gem", 4);
        Delta<Item> delta = new Delta<Item>().top(0, null).bag(12, item("gem", 1)).cursor(item("gem", 9));
        rig.tier1.apply(rig.escrow, rig.world, GUI, SLOTS_0_TO_8, delta);
        assertThat(rig.world.cursor()).isEqualTo(item("gem", 9));
        assertThat(rig.escrow.account().isEmpty()).isTrue();
        consistent();
    }

    @Test
    void aRefusedStepChangesNeitherSide() {
        // an account already at its cap of 54 entries: a placement would need a 55th
        List<Entry> owed = new ArrayList<>();
        for (int i = 1; i <= AccountCodec.MAX_ENTRIES; i++) {
            owed.add(Entry.held(new Id128(1, i), StackBytes.of(("gem:" + i).getBytes()), "old", 0, 1L).owed());
        }
        rig.escrow.open(new Account(1, owed));
        rig.world.cursor = item("coal", 3);
        var before = rig.world.copy();
        Tier1.Result<Item> result = click(InventoryAction.PLACE_ALL, 2, null);
        assertThat(result).isEqualTo(new Tier1.Denied<Item>(Tier1.Reason.UNRECORDABLE,
                dev.materione.plugin.escrow.model.Refusal.ENTRY_CAP));
        assertThat(rig.world.cursor()).isEqualTo(before.cursor);
        assertThat(rig.world.top(2)).isNull();
        assertThat(rig.written).isEmpty();
        assertThat(rig.escrow.account().entries()).hasSize(AccountCodec.MAX_ENTRIES);
    }

    @Test
    void anUnopenedGateRefusesEverythingThatTouchesARequiredSlot() {
        Rig closed = new Rig();
        closed.world.cursor = item("gem", 5);
        Delta<Item> delta = new Delta<Item>().top(2, item("gem", 5)).cursor(null);
        assertThat(closed.tier1.apply(closed.escrow, closed.world, GUI, SLOTS_0_TO_8, delta))
                .isEqualTo(new Tier1.Denied<Item>(Tier1.Reason.UNAVAILABLE, null));
        assertThat(closed.world.top(2)).isNull();
        assertThat(closed.world.cursor()).isEqualTo(item("gem", 5));
        assertThat(closed.written).isEmpty();
    }

    @Test
    void aWriteThatTouchesNoRequiredSlotNeedsNoGate() {
        Rig closed = new Rig();
        Delta<Item> delta = new Delta<Item>().top(30, item("gem", 1)).bag(1, item("coal", 1));
        assertThat(closed.tier1.apply(closed.escrow, closed.world, GUI, SLOTS_0_TO_8, delta))
                .isInstanceOf(Tier1.Done.class);
        assertThat(closed.world.top(30)).isEqualTo(item("gem", 1));
        assertThat(closed.written).isEmpty();
    }

    @Test
    void aFrozenSlotIsBusy() {
        rig.world.cursor = item("gem", 5);
        click(InventoryAction.PLACE_ALL, 2, null);
        Entry held = rig.escrow.account().entries().get(0);
        rig.escrow.open(new Account(5, List.of(held.exiting(new Id128(9, 9)))));
        Tier1.Result<Item> result = click(InventoryAction.PICKUP_ALL, 2, null);
        assertThat(result).isInstanceOf(Tier1.Denied.class);
        assertThat(((Tier1.Denied<Item>) result).reason()).isEqualTo(Tier1.Reason.BUSY);
        assertThat(rig.world.top(2)).isEqualTo(item("gem", 5));
    }

    @Test
    void aStackAnotherPluginChangedUnderUsIsAdoptedNotLost() {
        rig.world.cursor = item("gem", 5);
        click(InventoryAction.PLACE_ALL, 2, null);
        rig.world.top(2, item("gem", 9)); // someone else rewrote the slot
        rig.world.cursor = null;
        click(InventoryAction.PICKUP_ALL, 2, null);
        assertThat(rig.world.cursor()).isEqualTo(item("gem", 9));
        assertThat(rig.escrow.account().isEmpty()).isTrue();
        consistent();
    }

    @Test
    void theGenerationOnlyEverGrows() {
        long last = rig.escrow.account().gen();
        for (int i = 0; i < 6; i++) {
            rig.world.cursor = item("gem", 2);
            click(InventoryAction.PLACE_ALL, i, null);
            assertThat(Long.compareUnsigned(rig.escrow.account().gen(), last)).isGreaterThan(0);
            last = rig.escrow.account().gen();
        }
    }
}
