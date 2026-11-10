package dev.materione.plugin.escrow.runtime;

import static dev.materione.plugin.escrow.runtime.Rig.item;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.escrow.io.PlayerDatFiles;
import dev.materione.plugin.escrow.model.AccountRead;
import dev.materione.plugin.escrow.model.ExitKind;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.LedgerRecord;
import dev.materione.plugin.escrow.model.Recovery;
import dev.materione.plugin.escrow.model.StackBytes;
import dev.materione.plugin.escrow.runtime.BarrierRig.DiskMode;
import dev.materione.plugin.escrow.runtime.Rig.Item;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tier 2 steps 2 to 4 and the tier-3 barrier, end to end (gui-input-durability.md §5.4, §12 row B; K11 to K14, K23):
 * INTENT appended on the IO side, step 3 applied on the player's thread, the platform's save requested once per
 * cooldown, the file read back, the effect released only on proof, DONE appended. Every thread hop is a task of one
 * deterministic scheduler, so the tests stop between any two of them.
 */
class ExitFlowTest {

    @TempDir
    Path root;

    private BarrierRig r;

    @BeforeEach
    void setUp() {
        r = new BarrierRig(root, 5_000, 600_000);
    }

    @AfterEach
    void close() {
        r.close();
    }

    /** What an exit's owner is told, and what the disk held when the effect was allowed to run. */
    static final class Calls implements ExitCallbacks {

        final BarrierRig rig;
        final List<String> events = new ArrayList<>();
        long diskGenAtAck = Long.MIN_VALUE;
        boolean throwOnAck;
        int savesAtDecided = -1;
        boolean slotClearedAtDecided;
        /** Whether an effect waits for the proof (a flow that pays after consuming); the trash has none. */
        boolean effect = true;

        @Override
        public boolean carriesEffect() {
            return effect;
        }

        Calls(BarrierRig rig) {
            this.rig = rig;
        }

        @Override
        public void refused(Tier1.Reason reason) {
            events.add("refused(" + reason + ")");
        }

        @Override
        public void decided(boolean applied) {
            events.add("decided(" + applied + ")");
            savesAtDecided = rig.owner.saveTimes.size();
            slotClearedAtDecided = rig.rig.world.top(3) == null;
        }

        @Override
        public void acknowledged() {
            events.add("acknowledged");
            diskGenAtAck = rig.gen();
            if (throwOnAck) {
                throw new IllegalStateException("the effect failed");
            }
        }

        @Override
        public void abandoned(Abandoned why) {
            events.add("abandoned(" + why + ")");
        }

        boolean acknowledgedYet() {
            return events.contains("acknowledged");
        }
    }

    private Tier1.Begun begin(BarrierRig rig, int slot, Item stack) {
        rig.rig.world.cursor = stack;
        Tier1.Begin begun = rig.rig.tier1.beginExit(rig.rig.escrow, rig.rig.world, Rig.GUI, slot, stack,
                ExitKind.CONSUME, new Delta<Item>().cursor(null));
        assertThat(begun).isInstanceOf(Tier1.Begun.class);
        return (Tier1.Begun) begun;
    }

    private Tier1.Begun begin(int slot, Item stack) {
        return begin(r, slot, stack);
    }

    private Calls proceed(Tier1.Begun begun) {
        Calls calls = new Calls(r);
        r.exits.proceed(r.owner, begun, calls);
        return calls;
    }

    // ---- the whole path ----------------------------------------------------------------------------------------

    @Test
    void anExitIsDecidedAppliedSavedReadBackAcknowledgedAndRecordedDone() {
        Tier1.Begun begun = begin(3, item("gem", 5));
        Calls calls = proceed(begun);

        r.sim.runAll();

        assertThat(calls.events).containsExactly("decided(true)", "acknowledged");
        assertThat(r.owner.saveTimes).as("one platform save").hasSize(1);
        assertThat(calls.diskGenAtAck).as("the file held the exit's gen when the effect was released")
                .isGreaterThanOrEqualTo(begun.exitGen());
        assertThat(r.ledgerOnDisk().records()).containsExactly(begun.intent(), new LedgerRecord.Done(begun.txn()));
        assertThat(r.rig.escrow.account().entries()).as("step 3 deleted the entry").isEmpty();
        assertThat(r.rig.world.top(3)).as("the slot was cleared").isNull();
        assertThat(r.sim.nothingScheduled()).as("nothing left running").isTrue();
        assertThat(r.barrier.waiting(r.owner.id)).isZero();
    }

    @Test
    void theEffectIsNeverReleasedBeforeTheDiskProvesTheSnapshotAtAnyStep() {
        Tier1.Begun begun = begin(3, item("gem", 5));
        Calls calls = proceed(begun);

        int steps = 0;
        while (r.sim.runOne()) {
            steps++;
            if (calls.acknowledgedYet()) {
                assertThat(r.gen()).as("acknowledged at step %d", steps).isGreaterThanOrEqualTo(begun.exitGen());
                assertThat(r.ledgerOnDisk().isDecided(begun.txn())).as("the decision was durable first").isTrue();
            }
        }
        assertThat(calls.acknowledgedYet()).isTrue();
        assertThat(steps).as("the path really is several separate thread hops").isGreaterThanOrEqualTo(4);
    }

    @Test
    void anExitWithNoEffectSeesTheDeletionBeforeAnySave() {
        Calls calls = proceed(begin(3, item("gem", 5)));

        r.sim.runAll();

        assertThat(calls.savesAtDecided).as("step 3 ran before the platform save was requested").isZero();
        assertThat(calls.slotClearedAtDecided).as("the player already saw the deletion").isTrue();
        assertThat(calls.events).containsExactly("decided(true)", "acknowledged");
    }

    @Test
    void anExitWithNoEffectCostsNoSaveAndIsAcknowledgedByTheServersOwnSave() {
        Tier1.Begun begun = begin(3, item("gem", 5));
        Calls trash = new Calls(r);
        trash.effect = false;
        r.exits.proceed(r.owner, begun, trash);

        r.sim.runAll();
        assertThat(trash.events).containsExactly("decided(true)");
        assertThat(r.rig.world.top(3)).as("the deletion is visible at once").isNull();

        r.sim.advance(31_000); // a poll: the server has not saved yet
        assertThat(trash.acknowledgedYet()).isFalse();
        r.owner.writeSnapshot(); // the server's own autosave
        r.sim.advance(31_000);

        assertThat(trash.events).containsExactly("decided(true)", "acknowledged");
        assertThat(trash.diskGenAtAck).isGreaterThanOrEqualTo(begun.exitGen());
        assertThat(r.owner.saveTimes).as("D8: no Player.saveData() for an exit with no effect").isEmpty();
        assertThat(r.ledgerOnDisk().isDone(begun.txn())).isTrue();
        assertThat(r.log).as("polling for the server's save is not an alarm").noneMatch(line -> line.contains("did not confirm"));
    }

    @Test
    void oneSaveForAnEffectAlsoAcknowledgesAnExitWithNoEffectBesideIt() {
        Calls trash = new Calls(r);
        trash.effect = false;
        r.exits.proceed(r.owner, begin(1, item("gem", 5)), trash);
        Calls paid = proceed(begin(2, item("coal", 3)));

        r.sim.runAll();

        assertThat(r.owner.saveTimes).hasSize(1);
        assertThat(trash.events).containsExactly("decided(true)", "acknowledged");
        assertThat(paid.events).containsExactly("decided(true)", "acknowledged");
    }

    // ---- the rate limit -----------------------------------------------------------------------------------------

    @Test
    void severalExitsInTheSameMomentShareOneSave() {
        List<Calls> all = new ArrayList<>();
        for (int slot = 1; slot <= 3; slot++) {
            all.add(proceed(begin(slot, item("gem", slot))));
        }

        r.sim.runAll();

        assertThat(r.owner.saveTimes).hasSize(1);
        assertThat(all).allSatisfy(calls -> assertThat(calls.events).containsExactly("decided(true)", "acknowledged"));
        assertThat(r.ledgerOnDisk().records()).filteredOn(LedgerRecord.Done.class::isInstance).hasSize(3);
    }

    @Test
    void anExitBeginningAfterTheSaveWaitsForTheCooldownThenIsAcknowledgedByTheNextSave() {
        long t0 = r.sim.now.get();
        Tier1.Begun first = begin(1, item("gem", 5));
        Calls a = proceed(first);
        while (r.owner.saveTimes.isEmpty()) {
            assertThat(r.sim.runOne()).isTrue(); // stop right after saveData(), before its read-back is handled
        }
        Tier1.Begun second = begin(2, item("coal", 3));
        Calls b = proceed(second);

        r.sim.runAll();

        assertThat(a.events).containsExactly("decided(true)", "acknowledged");
        assertThat(b.events).as("the first save predates the second exit's step 1: not proven by it")
                .containsExactly("decided(true)");

        r.sim.advance(4_000);
        assertThat(b.acknowledgedYet()).as("inside the cooldown there is no second save").isFalse();
        assertThat(r.owner.saveTimes).hasSize(1);

        r.sim.advance(2_000);
        assertThat(r.owner.saveTimes).hasSize(2);
        assertThat(r.owner.saveTimes.get(1) - t0).as("once per cooldown").isGreaterThanOrEqualTo(5_000);
        assertThat(b.events).containsExactly("decided(true)", "acknowledged");
        assertThat(b.diskGenAtAck).isGreaterThanOrEqualTo(second.exitGen());
    }

    @Test
    void aSilentlyFailingSaveIsRetriedWithADoublingCooldownAndNeverAcknowledged() {
        r.owner.disk = DiskMode.STUCK;
        long t0 = r.sim.now.get();
        Calls calls = proceed(begin(3, item("gem", 5)));

        r.sim.advance(1);
        assertThat(r.owner.saveTimes).hasSize(1);
        r.sim.advance(9_000);
        assertThat(r.owner.saveTimes).as("a 10 s cooldown after one fruitless cycle").hasSize(1);
        r.sim.advance(1_100);
        assertThat(r.owner.saveTimes).hasSize(2);
        r.sim.advance(19_000);
        assertThat(r.owner.saveTimes).as("20 s after the second").hasSize(2);
        r.owner.disk = DiskMode.WORKS;
        r.sim.advance(2_000);

        assertThat(r.owner.saveTimes).hasSize(3);
        assertThat(r.owner.saveTimes.get(1) - t0).isBetween(10_000L, 10_100L);
        assertThat(r.owner.saveTimes.get(2) - r.owner.saveTimes.get(1)).isBetween(20_000L, 20_100L);
        assertThat(calls.events).as("acknowledged only by the save that really landed")
                .containsExactly("decided(true)", "acknowledged");
        assertThat(calls.diskGenAtAck).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aSaveThatThrowsIsLoggedAndTheReadBackStillDecides() {
        r.owner.disk = DiskMode.THROWS;
        Calls calls = proceed(begin(3, item("gem", 5)));

        r.sim.advance(1_000);

        assertThat(calls.events).containsExactly("decided(true)");
        assertThat(r.log).anyMatch(line -> line.contains("failed") && line.contains("not acknowledged"));
        r.owner.disk = DiskMode.WORKS;
        r.sim.advance(20_000);
        assertThat(calls.events).containsExactly("decided(true)", "acknowledged");
    }

    // ---- K23 ----------------------------------------------------------------------------------------------------

    @Test
    void k23_aKillBetweenTheSaveAndTheReadBackNeverRanTheEffectAndRecoveryAppliesTheIntent() {
        Tier1.Begun begun = begin(3, item("gem", 5));
        Calls calls = proceed(begun);
        while (r.owner.saveTimes.isEmpty()) {
            assertThat(r.sim.runOne()).isTrue();
        }
        // kill -9 here: saveData() returned, the read-back has not been handled

        assertThat(calls.acknowledgedYet()).as("the effect never ran").isFalse();
        assertThat(r.ledgerOnDisk().isDecided(begun.txn())).isTrue();
        assertThat(r.ledgerOnDisk().isDone(begun.txn())).as("no DONE without the proof").isFalse();
        assertThat(recoveredCount("gem")).as("recovery applies the INTENT: the stack is not returned").isZero();
    }

    @Test
    void k23_aStaleReadBackIsNotAnAcknowledgment() throws IOException {
        // the read-back finds an older gen: the file the platform "saved" is a snapshot from before the exit
        r.owner.disk = DiskMode.STUCK;
        PlayerDatFiles.save(r.playerdata, r.owner.id, PlayerDatFiles.account(0), 2);
        Tier1.Begun begun = begin(3, item("gem", 5));
        assertThat(begun.exitGen()).isGreaterThan(0);
        Calls calls = proceed(begun);

        r.sim.advance(8_000);

        assertThat(r.owner.saveTimes).isNotEmpty();
        assertThat(calls.events).containsExactly("decided(true)");
        assertThat(r.ledgerOnDisk().isDone(begun.txn())).isFalse();
    }

    @Test
    void k23_everyKillBoundaryKeepsTheRulesWithAndWithoutALateAutosave() throws IOException {
        int resurrectionsPrevented = 0;
        int returnedOnce = 0;
        int acknowledgments = 0;
        int cuts = 0;
        for (DiskMode mode : new DiskMode[] {DiskMode.WORKS, DiskMode.STUCK}) {
            for (boolean autosave : new boolean[] {false, true}) {
                for (int kill = 0; kill < 40; kill++) {
                    Path dir = Files.createTempDirectory(root, "k");
                    try (BarrierRig run = new BarrierRig(dir, 5_000, 600_000)) {
                        run.owner.disk = mode;
                        Tier1.Begun begun = begin(run, 3, item("gem", 5));
                        Calls calls = new Calls(run);
                        run.exits.proceed(run.owner, begun, calls);
                        boolean ended = false;
                        for (int step = 0; step < kill; step++) {
                            if (!run.sim.runOne()) {
                                ended = true;
                                break;
                            }
                        }
                        if (autosave) {
                            run.owner.writeSnapshot(); // an autosave lands just before the kill
                        }
                        String where = mode + " autosave=" + autosave + " kill=" + kill;
                        boolean decided = run.ledgerOnDisk().isDecided(begun.txn());
                        cuts++;
                        if (calls.acknowledgedYet()) {
                            acknowledgments++;
                            assertThat(decided).as("%s: acknowledged => decided", where).isTrue();
                            assertThat(calls.diskGenAtAck).as("%s: acknowledged => proven", where)
                                    .isGreaterThanOrEqualTo(begun.exitGen());
                        }
                        if (run.ledgerOnDisk().isDone(begun.txn())) {
                            assertThat(calls.acknowledgedYet()).as("%s: DONE => acknowledged", where).isTrue();
                        }
                        int returned = recoveredCount(run, "gem");
                        if (decided) {
                            assertThat(returned).as("%s: G2, a decided exit is never returned", where).isZero();
                            if (run.accountOnDisk() instanceof AccountRead.Readable disk && !disk.account().isEmpty()) {
                                resurrectionsPrevented++;
                            }
                        } else if (run.accountOnDisk() instanceof AccountRead.Readable disk
                                && !disk.account().isEmpty()) {
                            assertThat(returned).as("%s: an undecided frozen entry is returned once", where).isEqualTo(5);
                            returnedOnce++;
                        }
                        if (ended) {
                            break;
                        }
                    }
                }
            }
        }
        assertThat(cuts).as("many kill boundaries were exercised").isGreaterThan(20);
        assertThat(resurrectionsPrevented).as("snapshots holding a decided entry were recovered without it").isGreaterThan(0);
        assertThat(returnedOnce).as("snapshots holding an undecided entry were recovered with it").isGreaterThan(0);
        assertThat(acknowledgments).as("the sweep reached acknowledgments").isGreaterThan(0);
    }

    // ---- leaving, refusing, shutting down, giving up -------------------------------------------------------------

    @Test
    void aRefusedAppendAbortsTheExitAndReturnsTheStack() throws IOException {
        Files.createDirectories(r.escrowDir.getParent());
        Files.writeString(r.escrowDir, "not a directory"); // the ledger cannot be created: the append fails
        Tier1.Begun begun = begin(3, item("gem", 5));
        Calls calls = proceed(begun);

        r.sim.runAll();

        assertThat(calls.events).containsExactly("refused(UNRECORDABLE)");
        assertThat(r.rig.world.bagCount("gem")).as("nothing was consumed").isEqualTo(5);
        assertThat(r.rig.world.top(3)).isNull();
        assertThat(r.rig.escrow.account().entries()).isEmpty();
        assertThat(r.owner.saveTimes).as("nothing decided, so no barrier").isEmpty();
    }

    @Test
    void aClosedStoreRefusesAndAbortsToo() {
        Tier1.Begun begun = begin(3, item("gem", 5));
        r.store.close();
        Calls calls = proceed(begun);

        r.sim.runAll();

        assertThat(calls.events).containsExactly("refused(UNAVAILABLE)");
        assertThat(r.rig.world.bagCount("gem")).isEqualTo(5);
    }

    @Test
    void aPlayerWhoLeftBeforeStepThreeAbandonsTheEffectAndTheQuitSaveEarnsTheDone() throws IOException {
        Tier1.Begun begun = begin(3, item("gem", 5));
        Calls calls = proceed(begun);
        r.owner.online = false; // quit: the entity scheduler retires the continuation

        r.sim.runAll();

        assertThat(calls.events).containsExactly("abandoned(PLAYER_LEFT)");
        assertThat(r.ledgerOnDisk().isDecided(begun.txn())).as("the decision is durable: recovery finishes it").isTrue();
        assertThat(r.ledgerOnDisk().isDone(begun.txn())).isFalse();

        r.sim.advance(2_000); // the quit save has not landed: polled, nothing yet
        assertThat(r.ledgerOnDisk().isDone(begun.txn())).isFalse();
        PlayerDatFiles.save(r.playerdata, r.owner.id, rig().written.get(rig().written.size() - 1), 2);
        r.sim.advance(10_000);

        assertThat(r.ledgerOnDisk().isDone(begun.txn())).as("the quit save proved it").isTrue();
        assertThat(calls.events).as("the effect never ran").doesNotContain("acknowledged");
    }

    private Rig rig() {
        return r.rig;
    }

    @Test
    void aPlayerWhoLeavesWhileTheExitWaitsAbandonsItAndStillGetsTheDone() throws IOException {
        r.owner.disk = DiskMode.STUCK;
        Tier1.Begun begun = begin(3, item("gem", 5));
        Calls calls = proceed(begun);
        r.sim.advance(1_000);
        assertThat(calls.events).containsExactly("decided(true)");

        r.barrier.left(r.owner.id);
        r.owner.online = false;

        assertThat(calls.events).containsExactly("decided(true)", "abandoned(PLAYER_LEFT)");
        assertThat(r.barrier.waiting(r.owner.id)).isZero();
        PlayerDatFiles.save(r.playerdata, r.owner.id, rig().written.get(rig().written.size() - 1), 2);
        r.sim.advance(5_000);
        assertThat(r.ledgerOnDisk().isDone(begun.txn())).isTrue();
    }

    @Test
    void shutdownAbandonsWhatWaitsAndRefusesWhatComesLater() {
        r.owner.disk = DiskMode.STUCK;
        Calls waiting = proceed(begin(3, item("gem", 5)));
        r.sim.advance(1_000);

        r.barrier.close();

        assertThat(waiting.events).containsExactly("decided(true)", "abandoned(SHUTDOWN)");
        Calls late = new Calls(r);
        r.barrier.await(r.owner, LedgerRecordFixture.intent(77), 9, late);
        assertThat(late.events).containsExactly("abandoned(SHUTDOWN)");
    }

    @Test
    void anExitThatCannotBeProvenIsGivenUpAfterTheGiveUpTimeAndItsEffectNeverRuns() {
        r.owner.disk = DiskMode.STUCK;
        Tier1.Begun begun = begin(3, item("gem", 5));
        Calls calls = proceed(begun);

        r.sim.advance(10 * 60_000 + 5_000 + 300_000);

        assertThat(calls.events).containsExactly("decided(true)", "abandoned(TIMED_OUT)");
        int saves = r.owner.saveTimes.size();
        r.sim.advance(3_600_000);
        assertThat(r.owner.saveTimes).as("no more saves once nothing waits").hasSize(saves);
        assertThat(r.ledgerOnDisk().isDecided(begun.txn())).isTrue();
        assertThat(r.ledgerOnDisk().isDone(begun.txn())).isFalse();
        assertThat(r.log).anyMatch(line -> line.contains("gave up"));
    }

    @Test
    void anEffectThatThrowsDoesNotStopTheDone() {
        Tier1.Begun begun = begin(3, item("gem", 5));
        Calls calls = new Calls(r);
        calls.throwOnAck = true;
        r.exits.proceed(r.owner, begun, calls);

        r.sim.runAll();

        assertThat(calls.events).containsExactly("decided(true)", "acknowledged");
        assertThat(r.ledgerOnDisk().isDone(begun.txn())).isTrue();
        assertThat(r.log).anyMatch(line -> line.contains("effect failed"));
    }

    @Test
    void anExitFrozenInASlotTakesTheSamePath() {
        r.rig.world.cursor = item("gem", 5);
        r.rig.tier1.apply(r.rig.escrow, r.rig.world, Rig.GUI, Rig.SLOTS_0_TO_8,
                new Delta<Item>().top(2, item("gem", 5)).cursor(null));
        Tier1.Begin frozen = r.rig.tier1.freezeSlot(r.rig.escrow, r.rig.world, Rig.GUI, 2, ExitKind.CONSUME);
        Tier1.Begun begun = (Tier1.Begun) frozen;
        Calls calls = proceed(begun);

        r.sim.runAll();

        assertThat(calls.events).containsExactly("decided(true)", "acknowledged");
        assertThat(r.rig.world.top(2)).isNull();
        assertThat(r.rig.escrow.account().entries()).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** How many {@code id} a boot of the saved snapshot and the ledger on disk would return to the inventory. */
    private int recoveredCount(String id) {
        return recoveredCount(r, id);
    }

    private int recoveredCount(BarrierRig run, String id) {
        List<StackBytes> added = new ArrayList<>();
        var recovered = (Recovery.Result.Recovered) Recovery.recover(
                run.accountOnDisk() instanceof AccountRead.Readable readable ? readable.account()
                        : dev.materione.plugin.escrow.model.Account.EMPTY,
                run.ledgerOnDisk(), new dev.materione.plugin.escrow.model.Inventory() {
                    @Override
                    public boolean remove(StackBytes stack) {
                        return false;
                    }

                    @Override
                    public boolean tryAdd(StackBytes stack) {
                        added.add(stack);
                        return true;
                    }
                });
        int total = 0;
        for (StackBytes stack : recovered.returned()) {
            Item decoded = run.rig.stacks.decode(stack);
            total += decoded.id().equals(id) ? decoded.amount() : 0;
        }
        return total;
    }

    /** An INTENT with a fresh transaction, for a call that does not need a real exit. */
    static final class LedgerRecordFixture {

        static LedgerRecord.Intent intent(long n) {
            return new LedgerRecord.Intent(new Id128(8, n), new Id128(9, n), ExitKind.CONSUME, new Id128(1, 1), 1, 1L);
        }
    }
}
