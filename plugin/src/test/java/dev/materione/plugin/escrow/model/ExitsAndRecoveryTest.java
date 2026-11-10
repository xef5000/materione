package dev.materione.plugin.escrow.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tier 2's state machine and recovery (gui-input-durability.md §5.4, §5.7, K10-K18). */
class ExitsAndRecoveryTest {

    private static final Id128 TXN = Fx.id(0xAB);

    private static Step.Applied applied(Step step) {
        assertThat(step).isInstanceOf(Step.Applied.class);
        return (Step.Applied) step;
    }

    private static Recovery.Result.Recovered recovered(Recovery.Result result) {
        assertThat(result).isInstanceOf(Recovery.Result.Recovered.class);
        return (Recovery.Result.Recovered) result;
    }

    // ---- tier 2 -----------------------------------------------------------------------------------------------

    @Test
    void theFourStepsOfAConsumeIncomingStack() {
        var inventory = new Fx.ListInventory(4, "diamond");
        Entry exiting = Fx.held(1, "diamond").exiting(TXN);

        Step.Applied begun = applied(Exits.beginExit(Account.EMPTY, inventory, exiting));
        assertThat(inventory.stacks).isEmpty();
        assertThat(begun.account().entries()).containsExactly(exiting);

        // step 3 may not run before the decision is durable
        assertThat(Exits.complete(begun.account(), Ledger.EMPTY, TXN)).isEqualTo(new Step.Refused(Refusal.UNDECIDED));

        Ledger decided = Ledger.EMPTY.appended(Exits.intent(exiting, ExitKind.CONSUME, 1, 42));
        Step.Applied done = applied(Exits.complete(begun.account(), decided, TXN));
        assertThat(done.consumed()).containsExactly(Fx.stack("diamond"));
        assertThat(done.account().entries()).isEmpty();
        assertThat(inventory.stacks).isEmpty();

        // a second apply is a no-op: the entry id is gone
        assertThat(Exits.complete(done.account(), decided, TXN)).isEqualTo(new Step.NoOp(done.account()));
    }

    @Test
    void freezeTurnsAHeldEntryIntoAFrozenOneAndRefusesASecondFreeze() {
        Account account = Fx.account(1, Fx.held(1, "a"), Fx.held(2, "b"));
        Step.Applied frozen = applied(Exits.freeze(account, Fx.id(1), TXN));
        assertThat(frozen.account().find(Fx.id(1)).orElseThrow().state()).isEqualTo(EntryState.EXITING);
        assertThat(Exits.freeze(frozen.account(), Fx.id(1), Fx.id(0xCD))).isEqualTo(new Step.Refused(Refusal.BUSY));
        assertThat(Exits.freeze(frozen.account(), Fx.id(2), TXN)).isEqualTo(new Step.Refused(Refusal.INVALID));
        assertThat(Exits.freeze(account, Fx.id(404), TXN)).isEqualTo(new Step.Refused(Refusal.STALE));
        assertThat(Exits.freeze(account, Fx.id(1), Id128.ZERO)).isEqualTo(new Step.Refused(Refusal.INVALID));
    }

    @Test
    void anUndecidedExitCanBeAbortedAndADecidedOneCannot() {
        Entry exiting = Fx.held(1, "diamond").exiting(TXN);
        Account account = Fx.account(3, exiting);

        var inventory = new Fx.ListInventory(4);
        Step.Applied aborted = applied(Exits.abortUndecided(account, Ledger.EMPTY, inventory, TXN));
        assertThat(inventory.stacks).containsExactly(Fx.stack("diamond"));
        assertThat(aborted.account().entries()).isEmpty();
        assertThat(aborted.account().gen()).isEqualTo(4);

        var full = new Fx.ListInventory(0);
        Step.Applied owed = applied(Exits.abortUndecided(account, Ledger.EMPTY, full, TXN));
        assertThat(owed.account().entries()).containsExactly(Fx.held(1, "diamond").owed());
        assertThat(owed.account().gen()).isEqualTo(4);

        Ledger decided = Ledger.EMPTY.appended(Exits.intent(exiting, ExitKind.CONSUME, 1, 0));
        assertThat(Exits.abortUndecided(account, decided, inventory, TXN)).isEqualTo(new Step.Refused(Refusal.DECIDED));
    }

    @Test
    void anExitIsAcknowledgedOnlyOnceDecidedAndOnlyWhenTheSavedGenerationReachesIt() {
        Entry exiting = Fx.held(1, "diamond").exiting(TXN);
        LedgerRecord.Intent intent = Exits.intent(exiting, ExitKind.CONSUME, 1, 0);
        assertThat(Exits.acknowledge(Ledger.EMPTY, TXN, 5, 9)).as("not decided").isEmpty();
        Ledger decided = Ledger.EMPTY.appended(intent);
        assertThat(Exits.acknowledge(decided, TXN, 5, 4)).as("the saved snapshot predates the exit").isEmpty();
        assertThat(Exits.acknowledge(decided, TXN, 5, 5)).contains(new LedgerRecord.Done(TXN));
        assertThat(Exits.acknowledge(decided.appended(new LedgerRecord.Done(TXN)), TXN, 5, 9)).as("once").isEmpty();
        assertThat(Exits.acknowledge(decided, TXN, -1L, 5)).as("unsigned order").isEmpty();
    }

    // ---- recovery ---------------------------------------------------------------------------------------------

    @Test
    void recoveryReturnsHeldOwedAndUndecidedFrozenEntriesOnce() {
        Account saved = Fx.account(
                7,
                Fx.held(1, "held"),
                Fx.held(2, "owed").owed(),
                Fx.held(3, "frozen").exiting(TXN));
        var inventory = new Fx.ListInventory(8);
        Recovery.Result.Recovered result = recovered(Recovery.recover(saved, Ledger.EMPTY, inventory));

        assertThat(inventory.stacks).containsExactly(Fx.stack("held"), Fx.stack("owed"), Fx.stack("frozen"));
        assertThat(result.account().entries()).isEmpty();
        assertThat(result.account().gen()).isEqualTo(8);
        assertThat(result.encoded()).isPresent();
        assertThat(result.dropped()).isEmpty();
    }

    @Test
    void aDurableIntentDropsTheEntryWhateverItsStateOrTheSnapshotsAge() {
        Entry frozen = Fx.held(3, "frozen").exiting(TXN);
        Ledger ledger = Ledger.EMPTY.appended(Exits.intent(frozen, ExitKind.CONSUME, 1, 0));
        for (Entry stale : List.of(frozen, Fx.held(3, "frozen"), Fx.held(3, "frozen").owed())) {
            var inventory = new Fx.ListInventory(8);
            Recovery.Result.Recovered result =
                    recovered(Recovery.recover(Fx.account(2, stale), ledger, inventory));
            assertThat(inventory.stacks).as("G2 for %s", stale.state()).isEmpty();
            assertThat(result.dropped()).containsExactly(stale);
        }
    }

    @Test
    void anIntentForAnEntryTheSnapshotDoesNotHoldChangesNothing() {
        // G3: the snapshot predates the move-in, the item is still in the inventory
        Ledger ledger = Ledger.EMPTY.appended(Exits.intent(Fx.held(3, "diamond").exiting(TXN), ExitKind.CONSUME, 1, 0));
        var inventory = new Fx.ListInventory(8, "diamond");
        Recovery.Result.Recovered result = recovered(Recovery.recover(Account.EMPTY, ledger, inventory));
        assertThat(inventory.stacks).containsExactly(Fx.stack("diamond"));
        assertThat(result.encoded()).isEmpty();
    }

    @Test
    void whatDoesNotFitStaysOwedAndNothingIsDropped() {
        Account saved = Fx.account(7, Fx.held(1, "a"), Fx.held(2, "b"), Fx.held(3, "c"));
        var inventory = new Fx.ListInventory(1);
        Recovery.Result.Recovered result = recovered(Recovery.recover(saved, Ledger.EMPTY, inventory));
        assertThat(inventory.stacks).containsExactly(Fx.stack("a"));
        assertThat(result.owed()).containsExactly(Fx.stack("b"), Fx.stack("c"));
        assertThat(result.account().entries()).containsExactly(Fx.held(2, "b").owed(), Fx.held(3, "c").owed());
    }

    @Test
    void recoveryIsIdempotentAcrossACrashAndASecondRecovery() {
        Account saved = Fx.account(7, Fx.held(1, "a"), Fx.held(2, "b"), Fx.held(3, "c"));

        // the same inputs twice: the same result (a crash during recovery changes nothing on disk)
        var first = new Fx.ListInventory(1);
        var again = new Fx.ListInventory(1);
        Recovery.Result.Recovered a = recovered(Recovery.recover(saved, Ledger.EMPTY, first));
        Recovery.Result.Recovered b = recovered(Recovery.recover(saved, Ledger.EMPTY, again));
        assertThat(a.account()).isEqualTo(b.account());
        assertThat(a.encoded().orElseThrow()).isEqualTo(b.encoded().orElseThrow());
        assertThat(a.returned()).isEqualTo(b.returned());
        first.stacks.clear();
        first.stacks.addAll(again.stacks);

        // recovery saved, then a second crash: recovering the saved result with the same room changes nothing
        Recovery.Result.Recovered once = recovered(Recovery.recover(saved, Ledger.EMPTY, first));
        var inventoryAfterSave = new Fx.ListInventory(1, "a");
        Recovery.Result.Recovered twice = recovered(Recovery.recover(once.account(), Ledger.EMPTY, inventoryAfterSave));
        assertThat(twice.account()).isEqualTo(once.account());
        assertThat(twice.encoded()).isEmpty();
        assertThat(inventoryAfterSave.stacks).containsExactly(Fx.stack("a"));

        // with room it drains, and a drained account does nothing
        var roomy = new Fx.ListInventory(8, "a");
        Recovery.Result.Recovered drained = recovered(Recovery.recover(once.account(), Ledger.EMPTY, roomy));
        assertThat(drained.account().entries()).isEmpty();
        Recovery.Result.Recovered nothing = recovered(Recovery.recover(drained.account(), Ledger.EMPTY, roomy));
        assertThat(nothing.encoded()).isEqualTo(Optional.empty());
        assertThat(nothing.account()).isEqualTo(drained.account());
    }

    @Test
    void anUnreadableAccountOrLedgerRecoversNothingAndTouchesNothing() {
        var inventory = new Fx.ListInventory(8, "keep");
        byte[] bad = AccountCodec.encode(Fx.account(1, Fx.held(1, "a")));
        bad[bad.length - 1] ^= 1;

        assertThat(Recovery.recover(AccountCodec.decode(bad), new LedgerRead.Valid(Ledger.EMPTY, 6, 0), inventory))
                .isEqualTo(new Recovery.Result.Unrecoverable(Recovery.Cause.ACCOUNT, "BAD_CRC"));
        assertThat(Recovery.recover(
                        new AccountRead.Readable(Fx.account(1, Fx.held(1, "a"))),
                        new LedgerRead.Corrupt(6, "record checksum"),
                        inventory))
                .isInstanceOf(Recovery.Result.Unrecoverable.class);
        assertThat(Recovery.recover(new AccountRead.Absent(), new LedgerRead.Newer(2), inventory))
                .isEqualTo(new Recovery.Result.Unrecoverable(Recovery.Cause.LEDGER, "newer version 2"));
        assertThat(inventory.stacks).containsExactly(Fx.stack("keep"));
    }

    @Test
    void anAbsentAccountAndAFreshLedgerRecoverNothing() {
        var inventory = new Fx.ListInventory(8);
        Recovery.Result.Recovered result = recovered(Recovery.recover(
                new AccountRead.Absent(), new LedgerRead.Valid(Ledger.EMPTY, 0, 0), inventory));
        assertThat(result.encoded()).isEmpty();
        assertThat(result.account()).isEqualTo(Account.EMPTY);
    }
}
