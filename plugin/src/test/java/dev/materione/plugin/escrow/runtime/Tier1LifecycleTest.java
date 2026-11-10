package dev.materione.plugin.escrow.runtime;

import static dev.materione.plugin.escrow.runtime.Rig.GUI;
import static dev.materione.plugin.escrow.runtime.Rig.SLOTS_0_TO_8;
import static dev.materione.plugin.escrow.runtime.Rig.item;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.escrow.io.LedgerState;
import dev.materione.plugin.escrow.model.Account;
import dev.materione.plugin.escrow.model.AccountCodec;
import dev.materione.plugin.escrow.model.AccountRead;
import dev.materione.plugin.escrow.model.Entry;
import dev.materione.plugin.escrow.model.EntryState;
import dev.materione.plugin.escrow.model.ExitKind;
import dev.materione.plugin.escrow.model.Exits;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.Recovery;
import dev.materione.plugin.escrow.runtime.Rig.Item;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What a window's end, a claim, a death, a re-open and a join do to the account (gui-input-durability.md §5.5, §5.7):
 * the overflow is owed and never dropped, a death releases inside the event, the join recovery opens the gate and is
 * idempotent, and the legacy journal is adopted as owed entries.
 */
class Tier1LifecycleTest {

    private final Rig rig = Rig.open();
    private static final Set<Integer> SLOTS = Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8);

    private void put(int slot, Item stack) {
        rig.world.cursor = stack;
        Delta<Item> delta = new Delta<Item>().top(slot, stack).cursor(null);
        rig.tier1.apply(rig.escrow, rig.world, GUI, SLOTS_0_TO_8, delta);
    }

    private Tier1.Done<Item> done(Tier1.Result<Item> result) {
        assertThat(result).isInstanceOf(Tier1.Done.class);
        return (Tier1.Done<Item>) result;
    }

    // ---- close, overflow, claim ---------------------------------------------------------------------------------

    @Test
    void aCloseReturnsTheStacksToTheInventoryAndClearsTheSlots() {
        put(1, item("gem", 5));
        put(2, item("coal", 7));
        Tier1.Done<Item> result = done(rig.tier1.release(rig.escrow, rig.world, GUI, SLOTS));
        assertThat(result.owed()).isZero();
        assertThat(rig.world.bagCount("gem")).isEqualTo(5);
        assertThat(rig.world.bagCount("coal")).isEqualTo(7);
        assertThat(rig.world.top).isEmpty();
        assertThat(rig.persisted().isEmpty()).isTrue();
    }

    @Test
    void whatDoesNotFitIsOwedAndNeverDropped() {
        put(1, item("gem", 5));
        rig.world.fillBag("dirt");
        Tier1.Done<Item> result = done(rig.tier1.release(rig.escrow, rig.world, GUI, SLOTS));
        assertThat(result.owed()).isEqualTo(1);
        assertThat(result.strays()).isEmpty();
        assertThat(rig.world.top).isEmpty();
        assertThat(rig.persisted().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.state()).isEqualTo(EntryState.OWED));
        assertThat(rig.owned().get("gem")).isEqualTo(5); // still the player's, in the account
    }

    @Test
    void claimReturnsOwedStacksOnceThereIsRoomAndIsIdempotent() {
        put(1, item("gem", 5));
        rig.world.fillBag("dirt");
        rig.tier1.release(rig.escrow, rig.world, GUI, SLOTS);
        Tier1.Done<Item> stuck = done(rig.tier1.claim(rig.escrow, rig.world));
        assertThat(stuck.returned()).isZero();
        assertThat(stuck.owed()).isEqualTo(1);

        rig.world.bag[0] = null;
        Tier1.Done<Item> claimed = done(rig.tier1.claim(rig.escrow, rig.world));
        assertThat(claimed.returned()).isEqualTo(1);
        assertThat(claimed.owed()).isZero();
        assertThat(rig.world.bagCount("gem")).isEqualTo(5);
        long gen = rig.escrow.account().gen();
        assertThat(done(rig.tier1.claim(rig.escrow, rig.world)).returned()).isZero();
        assertThat(rig.escrow.account().gen()).as("a claim with nothing owed spends no generation").isEqualTo(gen);
    }

    @Test
    void theNextCloseRetriesWhatIsOwed() {
        put(1, item("gem", 5));
        rig.world.fillBag("dirt");
        rig.tier1.release(rig.escrow, rig.world, GUI, SLOTS);
        rig.world.bag[0] = null;
        rig.world.bag[1] = null;
        put(2, item("coal", 1));
        Tier1.Done<Item> result = done(rig.tier1.release(rig.escrow, rig.world, GUI, SLOTS));
        assertThat(result.owed()).isZero();
        assertThat(rig.world.bagCount("gem")).isEqualTo(5);
    }

    @Test
    void aReleaseNeverLosesAStackTheAccountDoesNotKnow() {
        rig.world.top(3, item("gem", 4)); // in a slot with no entry: a desync that must not eat items
        Tier1.Done<Item> result = done(rig.tier1.release(rig.escrow, rig.world, GUI, SLOTS));
        assertThat(rig.world.bagCount("gem")).isEqualTo(4);
        assertThat(result.strays()).isEmpty();
    }

    @Test
    void takeMovesToTheInventoryOrOwesIt() {
        put(1, item("gem", 5));
        done(rig.tier1.take(rig.escrow, rig.world, GUI, 1));
        assertThat(rig.world.bagCount("gem")).isEqualTo(5);
        assertThat(rig.world.top(1)).isNull();
        put(2, item("coal", 2));
        rig.world.fillBag("dirt");
        Tier1.Done<Item> owing = done(rig.tier1.take(rig.escrow, rig.world, GUI, 2));
        assertThat(owing.owed()).isEqualTo(1);
        assertThat(rig.world.top(2)).isNull();
    }

    @Test
    void editChangesTheEntryAndTheSlotTogether() {
        put(1, item("gem", 5));
        done(rig.tier1.edit(rig.escrow, rig.world, GUI, 1, item("gem", 2)));
        assertThat(rig.world.top(1)).isEqualTo(item("gem", 2));
        assertThat(rig.stacks.decode(rig.persisted().entries().get(0).stack())).isEqualTo(item("gem", 2));
        rig.assertConsistent(SLOTS);
    }

    // ---- a re-open ----------------------------------------------------------------------------------------------

    @Test
    void aCarriedStackStaysHeldAndTheOldWindowsCloseDoesNotReturnIt() {
        put(1, item("gem", 5));
        List<Tier1.Carried<Item>> carried = rig.tier1.beginCarry(rig.escrow, rig.world, SLOTS);
        assertThat(carried).hasSize(1);
        assertThat(rig.world.top).isEmpty();
        // the old window's close fires while the new one opens
        rig.tier1.release(rig.escrow, rig.world, GUI, SLOTS);
        assertThat(rig.world.bagCount("gem")).as("not returned: it is in flight").isZero();
        assertThat(rig.escrow.account().entries()).hasSize(1);

        rig.tier1.placeCarried(rig.escrow, rig.world, carried, slot -> slot == 1);
        assertThat(rig.world.top(1)).isEqualTo(item("gem", 5));
        assertThat(rig.escrow.carrying()).isEmpty();
        rig.assertConsistent(SLOTS);
    }

    @Test
    void aCarriedStackWhoseSlotStoppedBeingEscrowedGoesBackToThePlayer() {
        put(1, item("gem", 5));
        var carried = rig.tier1.beginCarry(rig.escrow, rig.world, SLOTS);
        rig.tier1.placeCarried(rig.escrow, rig.world, carried, slot -> false);
        assertThat(rig.world.bagCount("gem")).isEqualTo(5);
        assertThat(rig.escrow.account().isEmpty()).isTrue();
    }

    @Test
    void aWindowThatDidNotOpenGetsItsStacksBack() {
        put(1, item("gem", 5));
        var carried = rig.tier1.beginCarry(rig.escrow, rig.world, SLOTS);
        rig.tier1.restoreCarried(rig.escrow, rig.world, carried);
        assertThat(rig.world.top(1)).isEqualTo(item("gem", 5));
        rig.assertConsistent(SLOTS);
    }

    // ---- death --------------------------------------------------------------------------------------------------

    @Test
    void deathWithKeepInventoryReturnsTheStacksToTheInventory() {
        put(1, item("gem", 5));
        Tier1.Death<Item> death = rig.tier1.death(rig.escrow, rig.world, GUI, SLOTS, Ledger.EMPTY, true);
        assertThat(death.drops()).isEmpty();
        assertThat(rig.world.bagCount("gem")).isEqualTo(5);
        assertThat(rig.world.top).as("the slots are cleared, so the close that follows returns nothing again").isEmpty();
        assertThat(rig.persisted().isEmpty()).isTrue();
    }

    @Test
    void deathWithoutKeepInventoryAppendsTheStacksToTheDropList() {
        put(1, item("gem", 5));
        put(2, item("coal", 2));
        Tier1.Death<Item> death = rig.tier1.death(rig.escrow, rig.world, GUI, SLOTS, Ledger.EMPTY, false);
        assertThat(death.drops()).containsExactlyInAnyOrder(item("gem", 5), item("coal", 2));
        assertThat(rig.world.bagCount("gem")).isZero();
        assertThat(rig.world.top).isEmpty();
        assertThat(rig.persisted().isEmpty()).isTrue();
        // the close the server does next finds nothing: no second copy
        Tier1.Done<Item> close = done(rig.tier1.release(rig.escrow, rig.world, GUI, SLOTS));
        assertThat(close.returned()).isZero();
        assertThat(rig.world.bagCount("gem")).isZero();
    }

    @Test
    void deathWithAFullInventoryAndKeepInventoryOwesTheOverflow() {
        put(1, item("gem", 5));
        rig.world.fillBag("dirt");
        Tier1.Death<Item> death = rig.tier1.death(rig.escrow, rig.world, GUI, SLOTS, Ledger.EMPTY, true);
        assertThat(death.owed()).isEqualTo(1);
        assertThat(death.drops()).isEmpty();
        assertThat(rig.persisted().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.state()).isEqualTo(EntryState.OWED));
    }

    @Test
    void deathFollowsTheLedgerForAFrozenEntry() {
        put(1, item("gem", 5));
        Entry held = rig.escrow.account().entries().get(0);
        Entry frozen = held.exiting(new Id128(4, 4));
        rig.escrow.open(new Account(9, List.of(frozen)));
        Ledger decided = Ledger.EMPTY.appended(Exits.intent(frozen, ExitKind.CONSUME, 5, 1L));
        Tier1.Death<Item> death = rig.tier1.death(rig.escrow, rig.world, GUI, SLOTS, decided, false);
        assertThat(death.consumed()).as("a durable decision is never returned (G2)").isEqualTo(1);
        assertThat(death.drops()).isEmpty();
    }

    @Test
    void deathOfAPlayerWhoseGateIsClosedTouchesNothing() {
        Rig closed = new Rig();
        closed.world.top(1, item("gem", 5));
        Tier1.Death<Item> death = closed.tier1.death(closed.escrow, closed.world, GUI, SLOTS, Ledger.EMPTY, false);
        assertThat(death.drops()).isEmpty();
        assertThat(closed.world.top(1)).isEqualTo(item("gem", 5));
    }

    // ---- join recovery ------------------------------------------------------------------------------------------

    private static LedgerState ready(Ledger ledger) {
        return new LedgerState.Ready(ledger);
    }

    private Account crashed() {
        // a snapshot taken with two stacks in a window: one HELD, one OWED
        Entry held = Entry.held(new Id128(1, 1), rig.stacks.encode(item("gem", 5)), GUI, 1, 1L);
        Entry owed = Entry.held(new Id128(1, 2), rig.stacks.encode(item("coal", 3)), GUI, 2, 1L).owed();
        return new Account(7, List.of(held, owed));
    }

    @Test
    void joinRecoveryReturnsEverythingOnceAndOpensTheGate() {
        Rig joined = new Rig();
        AccountRead saved = new AccountRead.Readable(crashed());
        Tier1.Recovered result = joined.tier1.recover(joined.escrow, joined.world, saved, ready(Ledger.EMPTY), List.of());
        assertThat(result).isEqualTo(new Tier1.Opened(2, 0, 0, 0, false));
        assertThat(joined.escrow.open()).isTrue();
        assertThat(joined.world.bagCount("gem")).isEqualTo(5);
        assertThat(joined.world.bagCount("coal")).isEqualTo(3);
        assertThat(joined.persisted().isEmpty()).isTrue();
    }

    @Test
    void recoveringTheSameSnapshotTwiceGivesTheSameInventory() {
        AccountRead saved = new AccountRead.Readable(crashed());
        Rig first = new Rig();
        first.tier1.recover(first.escrow, first.world, saved, ready(Ledger.EMPTY), List.of());
        Rig second = new Rig();
        second.tier1.recover(second.escrow, second.world, saved, ready(Ledger.EMPTY), List.of());
        assertThat(second.owned()).isEqualTo(first.owned());
        // and a drained account does nothing, spends no write
        int writes = first.written.size();
        first.tier1.recover(first.escrow, first.world, new AccountRead.Readable(first.escrow.account()),
                ready(Ledger.EMPTY), List.of());
        assertThat(first.written).hasSize(writes);
        assertThat(first.world.bagCount("gem")).isEqualTo(5);
    }

    @Test
    void aFullInventoryLeavesTheStacksOwedAndTheGateOpen() {
        Rig joined = new Rig();
        joined.world.fillBag("dirt");
        Tier1.Recovered result = joined.tier1.recover(joined.escrow, joined.world,
                new AccountRead.Readable(crashed()), ready(Ledger.EMPTY), List.of());
        assertThat(result).isEqualTo(new Tier1.Opened(0, 2, 0, 0, false));
        assertThat(joined.escrow.open()).isTrue();
        assertThat(Tier1.countOwed(joined.escrow.account())).isEqualTo(2);
    }

    @Test
    void aDurableConsumeDecisionIsNeverResurrected() {
        Account snapshot = crashed();
        Entry gone = snapshot.entries().get(0);
        Ledger decided = Ledger.EMPTY.appended(Exits.intent(gone.exiting(new Id128(3, 3)), ExitKind.CONSUME, 5, 1L));
        Rig joined = new Rig();
        Tier1.Recovered result = joined.tier1.recover(joined.escrow, joined.world,
                new AccountRead.Readable(snapshot), ready(decided), List.of());
        assertThat(((Tier1.Opened) result).dropped()).isEqualTo(1);
        assertThat(joined.world.bagCount("gem")).isZero();
        assertThat(joined.world.bagCount("coal")).isEqualTo(3);
    }

    @Test
    void anUnreadableAccountKeepsTheGateShutAndTouchesNothing() {
        Rig joined = new Rig();
        byte[] corrupt = AccountCodec.encode(crashed());
        corrupt[corrupt.length - 6] ^= 1;
        Tier1.Recovered result = joined.tier1.recover(joined.escrow, joined.world, AccountCodec.decode(corrupt),
                ready(Ledger.EMPTY), List.of());
        assertThat(result).isInstanceOf(Tier1.Blocked.class);
        assertThat(((Tier1.Blocked) result).cause()).isEqualTo(Recovery.Cause.ACCOUNT);
        assertThat(joined.escrow.gate()).isEqualTo(PlayerEscrow.Gate.UNRECOVERABLE);
        assertThat(joined.written).isEmpty();
        assertThat(joined.world.bagCount("gem")).isZero();
        // and a strict click is refused while it is unresolved
        Delta<Item> delta = new Delta<Item>().top(1, item("gem", 1));
        assertThat(joined.tier1.apply(joined.escrow, joined.world, GUI, SLOTS_0_TO_8, delta))
                .isEqualTo(new Tier1.Denied<Item>(Tier1.Reason.UNAVAILABLE, null));
    }

    @Test
    void anUnrecoverableLedgerKeepsTheGateShut() {
        Rig joined = new Rig();
        LedgerState broken = new LedgerState.Unrecoverable(LedgerState.Why.CORRUPT, "x.ledger", "bad record");
        Tier1.Recovered result = joined.tier1.recover(joined.escrow, joined.world,
                new AccountRead.Readable(crashed()), broken, List.of());
        assertThat(((Tier1.Blocked) result).cause()).isEqualTo(Recovery.Cause.LEDGER);
        assertThat(joined.escrow.open()).isFalse();
        assertThat(joined.world.bagCount("gem")).isZero();
    }

    // ---- the legacy journal -------------------------------------------------------------------------------------

    @Test
    void theLegacyJournalIsAdoptedAsOwedEntriesAndClaimedInTheSameJoin() {
        Rig joined = new Rig();
        Tier1.Recovered result = joined.tier1.recover(joined.escrow, joined.world, new AccountRead.Absent(),
                ready(Ledger.EMPTY), List.of(item("gem", 3), item("coal", 1)));
        assertThat(result).isEqualTo(new Tier1.Opened(2, 0, 0, 2, false));
        assertThat(joined.world.bagCount("gem")).isEqualTo(3);
        assertThat(joined.persisted().isEmpty()).isTrue();
    }

    @Test
    void aFullInventoryLeavesTheAdoptedLegacyStacksOwedNotDropped() {
        Rig joined = new Rig();
        joined.world.fillBag("dirt");
        Tier1.Recovered result = joined.tier1.recover(joined.escrow, joined.world, new AccountRead.Absent(),
                ready(Ledger.EMPTY), List.of(item("gem", 3)));
        assertThat(result).isEqualTo(new Tier1.Opened(0, 1, 0, 1, false));
        assertThat(joined.persisted().entries()).singleElement().satisfies(entry -> {
            assertThat(entry.state()).isEqualTo(EntryState.OWED);
            assertThat(entry.originGui()).isEqualTo("legacy:gui_inputs");
        });
    }

    @Test
    void anAccountAtItsCapLeavesTheLegacyJournalInPlace() {
        Rig joined = new Rig();
        var entries = new java.util.ArrayList<Entry>();
        for (int i = 1; i <= AccountCodec.MAX_ENTRIES; i++) {
            entries.add(Entry.held(new Id128(2, i), joined.stacks.encode(item("g" + i, 64)), "old", 0, 1L).owed());
        }
        joined.world.fillBag("dirt");
        Tier1.Recovered result = joined.tier1.recover(joined.escrow, joined.world,
                new AccountRead.Readable(new Account(3, entries)), ready(Ledger.EMPTY), List.of(item("gem", 3)));
        assertThat(((Tier1.Opened) result).legacyKept()).isTrue();
        assertThat(((Tier1.Opened) result).adopted()).isZero();
    }
}
