package dev.materione.plugin.escrow.runtime;

import static dev.materione.plugin.escrow.runtime.Rig.GUI;
import static dev.materione.plugin.escrow.runtime.Rig.SLOTS_0_TO_8;
import static dev.materione.plugin.escrow.runtime.Rig.item;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.escrow.model.Account;
import dev.materione.plugin.escrow.model.AccountCodec;
import dev.materione.plugin.escrow.model.Entry;
import dev.materione.plugin.escrow.model.EntryState;
import dev.materione.plugin.escrow.model.ExitKind;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.LedgerRecord;
import dev.materione.plugin.escrow.model.Refusal;
import dev.materione.plugin.escrow.runtime.Rig.Item;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tier 2's owner-thread steps on the one tier-1 API (gui-input-durability.md §5.4 steps 1 and 3; row B): the entry
 * points #248's consume action calls. Each is one step that takes both sides, so a refusal changes neither.
 */
class Tier1ExitsTest {

    private final Rig rig = Rig.open();

    private Tier1.Begun begin(int slot, Item incoming) {
        rig.world.cursor = incoming;
        Tier1.Begin result = rig.tier1.beginExit(rig.escrow, rig.world, GUI, slot, incoming, ExitKind.CONSUME,
                new Delta<Item>().cursor(null));
        assertThat(result).isInstanceOf(Tier1.Begun.class);
        return (Tier1.Begun) result;
    }

    private static Ledger decided(Tier1.Begun begun) {
        return new Ledger(List.of(begun.intent()));
    }

    @Test
    void stepOneMovesTheIncomingStackIntoAFrozenEntryAndShowsItInTheSlot() {
        Tier1.Begun begun = begin(3, item("gem", 5));

        assertThat(rig.world.cursor).as("the source was emptied in the same step").isNull();
        assertThat(rig.world.top(3)).as("the frozen stack stays visible in its slot").isEqualTo(item("gem", 5));
        Entry entry = rig.escrow.account().find(begun.entry()).orElseThrow();
        assertThat(entry.state()).isEqualTo(EntryState.EXITING);
        assertThat(entry.txn()).isEqualTo(begun.txn());
        assertThat(entry.slotHint()).isEqualTo(3);
        assertThat(begun.exitGen()).as("the gen of the account after step 1").isEqualTo(rig.escrow.account().gen());
        assertThat(rig.persisted()).as("the persistent value is the live account (I1)").isEqualTo(rig.escrow.account());
        assertThat(begun.intent().amount()).isEqualTo(5);
        assertThat(begun.intent().kind()).isEqualTo(ExitKind.CONSUME);
        assertThat(begun.intent().entryId()).isEqualTo(begun.entry());
        assertThat(begun.intent().txn()).isEqualTo(begun.txn());
    }

    @Test
    void aFrozenSlotRefusesEveryClickAsBusy() {
        begin(3, item("gem", 5));
        rig.world.cursor = item("coal", 1);

        Tier1.Result<Item> click = rig.tier1.apply(rig.escrow, rig.world, GUI, SLOTS_0_TO_8,
                new Delta<Item>().top(3, item("coal", 1)).cursor(null));

        assertThat(click).isInstanceOfSatisfying(Tier1.Denied.class, denied -> assertThat(denied.reason()).isEqualTo(Tier1.Reason.BUSY));
        assertThat(rig.world.top(3)).isEqualTo(item("gem", 5));
        assertThat(rig.world.cursor).isEqualTo(item("coal", 1));
    }

    @Test
    void aCloseLeavesTheFrozenEntryInTheAccountAndItsSlotShown() {
        begin(3, item("gem", 5));

        rig.tier1.release(rig.escrow, rig.world, GUI, List.of(3));

        assertThat(rig.escrow.account().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.state()).isEqualTo(EntryState.EXITING));
        assertThat(rig.world.bagCount("gem")).as("a frozen entry is never returned by a close (K14)").isZero();
    }

    @Test
    void stepOneIsRefusedWithoutTouchingTheSourceWhenItCannotBeRecorded() {
        // a closed gate, an occupied slot, an empty stack, an account at its cap
        Rig closed = new Rig();
        closed.world.cursor = item("gem", 5);
        Tier1.Begin gate = closed.tier1.beginExit(closed.escrow, closed.world, GUI, 3, item("gem", 5), ExitKind.CONSUME,
                new Delta<Item>().cursor(null));
        assertThat(gate).isEqualTo(new Tier1.Declined(Tier1.Reason.UNAVAILABLE, null));
        assertThat(closed.world.cursor).isEqualTo(item("gem", 5));

        rig.world.top(3, item("coal", 1));
        rig.world.cursor = item("gem", 5);
        Tier1.Begin occupied = rig.tier1.beginExit(rig.escrow, rig.world, GUI, 3, item("gem", 5), ExitKind.CONSUME,
                new Delta<Item>().cursor(null));
        assertThat(occupied).isInstanceOfSatisfying(Tier1.Declined.class,
                declined -> assertThat(declined.reason()).isEqualTo(Tier1.Reason.BUSY));
        assertThat(rig.world.cursor).as("the source is untouched").isEqualTo(item("gem", 5));

        rig.world.top(3, null);
        assertThat(rig.tier1.beginExit(rig.escrow, rig.world, GUI, 4, null, ExitKind.CONSUME, new Delta<Item>()))
                .isInstanceOf(Tier1.Declined.class);

        List<Entry> full = new ArrayList<>();
        for (int i = 0; i < AccountCodec.MAX_ENTRIES; i++) {
            full.add(Entry.held(new Id128(5, i + 1), rig.stacks.encode(item("x" + i, 1)), GUI, 20 + i, 0));
        }
        Rig capped = new Rig();
        capped.escrow.open(new Account(1, full));
        capped.world.cursor = item("gem", 5);
        Tier1.Begin over = capped.tier1.beginExit(capped.escrow, capped.world, GUI, 3, item("gem", 5), ExitKind.CONSUME,
                new Delta<Item>().cursor(null));
        assertThat(over).isInstanceOfSatisfying(Tier1.Declined.class,
                declined -> assertThat(declined.reason()).isEqualTo(Tier1.Reason.UNRECORDABLE));
        assertThat(capped.world.cursor).as("the cap refusal left the cursor alone").isEqualTo(item("gem", 5));
        assertThat(capped.world.top(3)).isNull();
    }

    @Test
    void freezingAStackAlreadyInASlotKeepsItShownAndFrozen() {
        rig.world.cursor = item("gem", 5);
        rig.tier1.apply(rig.escrow, rig.world, GUI, SLOTS_0_TO_8, new Delta<Item>().top(2, item("gem", 5)).cursor(null));
        long before = rig.escrow.account().gen();

        Tier1.Begin result = rig.tier1.freezeSlot(rig.escrow, rig.world, GUI, 2, ExitKind.CONSUME);

        assertThat(result).isInstanceOfSatisfying(Tier1.Begun.class, begun -> {
            assertThat(begun.exitGen()).isGreaterThan(before);
            assertThat(begun.intent().amount()).isEqualTo(5);
            assertThat(rig.escrow.account().findByTxn(begun.txn())).isPresent();
        });
        assertThat(rig.world.top(2)).isEqualTo(item("gem", 5));
        assertThat(rig.tier1.freezeSlot(rig.escrow, rig.world, GUI, 2, ExitKind.CONSUME))
                .as("a second freeze is busy").isInstanceOfSatisfying(Tier1.Declined.class,
                        declined -> assertThat(declined.reason()).isEqualTo(Tier1.Reason.BUSY));
        assertThat(rig.tier1.freezeSlot(rig.escrow, rig.world, GUI, 6, ExitKind.CONSUME))
                .as("nothing in slot 6").isInstanceOf(Tier1.Declined.class);
    }

    @Test
    void stepThreeDeletesTheEntryClearsTheSlotAndPersists() {
        Tier1.Begun begun = begin(3, item("gem", 5));
        long exitGen = rig.escrow.account().gen();

        Tier1.Finish result = rig.tier1.completeExit(rig.escrow, rig.world, decided(begun), begun.txn());

        assertThat(result).isEqualTo(new Tier1.Finished(true, 0, 0));
        assertThat(rig.world.top(3)).isNull();
        assertThat(rig.escrow.account().entries()).isEmpty();
        assertThat(rig.escrow.account().gen()).isGreaterThan(exitGen);
        assertThat(rig.persisted()).isEqualTo(rig.escrow.account());
        assertThat(rig.world.bagCount("gem")).as("consumed, not returned").isZero();
    }

    @Test
    void stepThreeNeverRunsBeforeTheDecisionIsDurable() {
        Tier1.Begun begun = begin(3, item("gem", 5));
        Account before = rig.escrow.account();

        Tier1.Finish result = rig.tier1.completeExit(rig.escrow, rig.world, Ledger.EMPTY, begun.txn());

        assertThat(result).isEqualTo(new Tier1.Declined(Tier1.Reason.UNRECORDABLE, Refusal.UNDECIDED));
        assertThat(rig.escrow.account()).isEqualTo(before);
        assertThat(rig.world.top(3)).isEqualTo(item("gem", 5));
    }

    @Test
    void stepThreeTwiceIsANoOpTheSecondTime() {
        Tier1.Begun begun = begin(3, item("gem", 5));
        rig.tier1.completeExit(rig.escrow, rig.world, decided(begun), begun.txn());
        long gen = rig.escrow.account().gen();

        assertThat(rig.tier1.completeExit(rig.escrow, rig.world, decided(begun), begun.txn()))
                .isEqualTo(new Tier1.Finished(false, 0, 0));
        assertThat(rig.escrow.account().gen()).as("a no-op spends no generation").isEqualTo(gen);
    }

    @Test
    void stepThreeLeavesASlotAloneThatNoLongerShowsTheFrozenStack() {
        Tier1.Begun begun = begin(3, item("gem", 5));
        rig.world.top(3, item("other", 1)); // the window was replaced by one with a different stack at that slot

        rig.tier1.completeExit(rig.escrow, rig.world, decided(begun), begun.txn());

        assertThat(rig.world.top(3)).isEqualTo(item("other", 1));
        assertThat(rig.escrow.account().entries()).isEmpty();
    }

    @Test
    void anUndecidedExitCanBeAbortedAndTheStackGoesBackToTheInventory() {
        Tier1.Begun begun = begin(3, item("gem", 5));

        Tier1.Finish result = rig.tier1.abortExit(rig.escrow, rig.world, Ledger.EMPTY, begun.txn());

        assertThat(result).isEqualTo(new Tier1.Finished(true, 1, 0));
        assertThat(rig.world.bagCount("gem")).isEqualTo(5);
        assertThat(rig.world.top(3)).isNull();
        assertThat(rig.escrow.account().entries()).isEmpty();
        assertThat(rig.persisted()).isEqualTo(rig.escrow.account());
    }

    @Test
    void anAbortWithAFullInventoryOwesTheStackNeverDropsIt() {
        Tier1.Begun begun = begin(3, item("gem", 5));
        rig.world.fillBag("dirt");

        Tier1.Finish result = rig.tier1.abortExit(rig.escrow, rig.world, Ledger.EMPTY, begun.txn());

        assertThat(result).isEqualTo(new Tier1.Finished(true, 0, 1));
        assertThat(rig.escrow.account().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.state()).isEqualTo(EntryState.OWED));
        assertThat(rig.owned().get("gem")).isEqualTo(5);
    }

    @Test
    void thereIsNoAbortAfterTheDecision() {
        Tier1.Begun begun = begin(3, item("gem", 5));
        Account before = rig.escrow.account();

        Tier1.Finish result = rig.tier1.abortExit(rig.escrow, rig.world, decided(begun), begun.txn());

        assertThat(result).isEqualTo(new Tier1.Declined(Tier1.Reason.UNRECORDABLE, Refusal.DECIDED));
        assertThat(rig.escrow.account()).isEqualTo(before);
        assertThat(rig.world.top(3)).isEqualTo(item("gem", 5));
    }

    @Test
    void theIntentNamesTheExitsStackByHashAndTheLedgerRecordRoundTrips() {
        Tier1.Begun begun = begin(1, item("gem", 5));
        Entry entry = rig.escrow.account().find(begun.entry()).orElseThrow();

        assertThat(begun.intent()).isEqualTo(new LedgerRecord.Intent(begun.txn(), begun.entry(), ExitKind.CONSUME,
                entry.stack().hash16(), 5, 1_000L));
    }
}
