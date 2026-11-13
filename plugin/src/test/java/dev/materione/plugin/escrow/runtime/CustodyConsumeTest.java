package dev.materione.plugin.escrow.runtime;

import static dev.materione.plugin.escrow.runtime.Rig.item;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.escrow.io.PlayerDatFiles;
import dev.materione.plugin.escrow.model.AccountRead;
import dev.materione.plugin.escrow.model.Recovery;
import dev.materione.plugin.escrow.model.StackBytes;
import dev.materione.plugin.escrow.runtime.BarrierRig.DiskMode;
import dev.materione.plugin.escrow.runtime.CustodyConsume.Reach;
import dev.materione.plugin.escrow.runtime.Rig.Item;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The custody consume primitive (#248; gui-input-durability.md §5.4, §12 row X), over the real ledger store, read-back,
 * barrier and exit runner of {@link BarrierRig}: a stack sits in custody at a window slot, and {@link CustodyConsume}
 * takes it through the tier-2 exit. What the flow after it may rely on is the outcome that completes: at
 * {@link Reach#DECIDED} nothing waits for a save; at {@link Reach#SAVED} it completes only after the disk proves it.
 */
class CustodyConsumeTest {

    @TempDir
    Path root;

    private BarrierRig r;
    private CustodyConsume<Item> consume;

    @BeforeEach
    void setUp() {
        r = new BarrierRig(root, 5_000, 600_000);
        consume = new CustodyConsume<>(r.rig.tier1, r.exits);
    }

    @AfterEach
    void close() {
        r.close();
    }

    /** Puts {@code stack} in custody at {@code slot}: the HELD entry a click on a required slot makes. */
    private void hold(int slot, Item stack) {
        r.rig.world.cursor = stack;
        Tier1.Result<Item> result = r.rig.tier1.apply(r.rig.escrow, r.rig.world, Rig.GUI, Rig.SLOTS_0_TO_8,
                new Delta<Item>().top(slot, stack).cursor(null));
        assertThat(result).isInstanceOf(Tier1.Done.class);
    }

    private CompletionStage<CustodyConsume.Outcome> running(CustodyConsume.Started<Item> started) {
        assertThat(started).isInstanceOf(CustodyConsume.Running.class);
        return ((CustodyConsume.Running<Item>) started).outcome();
    }

    private static Optional<CustodyConsume.Outcome> settled(CompletionStage<CustodyConsume.Outcome> stage) {
        var future = stage.toCompletableFuture();
        return future.isDone() ? Optional.of(future.join()) : Optional.empty();
    }

    // ---- a plain consume -----------------------------------------------------------------------------------------

    @Test
    void aPlainConsumeCompletesWhenDecidedAndAppliedWithoutAnySave() {
        hold(3, item("gem", 5));
        var stage = running(consume.consume(r.owner, Rig.GUI, 3, Reach.DECIDED));
        assertThat(settled(stage)).as("step 1 is synchronous, the decision is not").isEmpty();
        assertThat(r.rig.world.top(3)).as("frozen and still shown until step 3").isEqualTo(item("gem", 5));

        r.sim.runAll();

        assertThat(settled(stage)).contains(new CustodyConsume.Consumed(Reach.DECIDED));
        assertThat(r.rig.world.top(3)).as("the stack is gone").isNull();
        assertThat(r.rig.escrow.account().entries()).isEmpty();
        assertThat(r.rig.world.bagCount("gem")).as("consumed, not returned").isZero();
        assertThat(r.owner.saveTimes).as("D8: a plain consume asks for no Player.saveData()").isEmpty();
        assertThat(r.ledgerOnDisk().records()).as("one INTENT, no DONE until a snapshot proves it").hasSize(1);
        assertThat(r.ledgerOnDisk().isDecided(r.ledgerOnDisk().records().get(0).txn())).isTrue();
    }

    @Test
    void aPlainConsumeIsStillAcknowledgedLaterByTheServersOwnSaveAndRecordsDone() {
        hold(3, item("gem", 5));
        var stage = running(consume.consume(r.owner, Rig.GUI, 3, Reach.DECIDED));
        r.sim.runAll();
        assertThat(settled(stage)).isPresent();

        r.owner.writeSnapshot(); // the server's own autosave
        r.sim.advance(31_000);

        assertThat(r.owner.saveTimes).isEmpty();
        assertThat(r.ledgerOnDisk().records()).hasSize(2); // INTENT + DONE
    }

    // ---- a consume that carries an effect ------------------------------------------------------------------------

    @Test
    void aConsumeThatWaitsForTheSaveReleasesItsEffectOnlyAfterTheDiskProvesTheSnapshot() {
        hold(3, item("gem", 5));
        List<String> order = new ArrayList<>();
        var stage = running(consume.consume(r.owner, Rig.GUI, 3, Reach.SAVED));
        stage.thenAccept(outcome -> order.add("effect(diskGen=" + r.gen() + ")"));

        int hops = 0;
        long exitGen = r.rig.escrow.account().gen();
        while (r.sim.runOne()) {
            hops++;
            if (settled(stage).isPresent()) {
                assertThat(r.gen()).as("the effect ran only once the file held the consume (hop %d)", hops)
                        .isGreaterThanOrEqualTo(exitGen);
            }
        }

        assertThat(settled(stage)).contains(new CustodyConsume.Consumed(Reach.SAVED));
        assertThat(order).hasSize(1);
        assertThat(r.owner.saveTimes).as("an effect asks for the platform save, once").hasSize(1);
        assertThat(r.rig.world.top(3)).isNull();
        assertThat(r.ledgerOnDisk().records()).as("INTENT then DONE").hasSize(2);
        assertThat(r.rig.world.bagCount("gem")).isZero();
    }

    @Test
    void theEffectNeverRunsWhileTheDiskCannotSaveAndGivesUpAfterTheBarrierDoes() {
        r.owner.disk = DiskMode.STUCK;
        hold(3, item("gem", 5));
        var stage = running(consume.consume(r.owner, Rig.GUI, 3, Reach.SAVED));

        r.sim.advance(60_000);
        assertThat(settled(stage)).as("not acknowledged: the read-back finds nothing newer").isEmpty();
        assertThat(r.rig.world.top(3)).as("the consume itself is applied: the player sees it").isNull();
        assertThat(r.ledgerOnDisk().records()).as("decided").hasSize(1);

        r.sim.advance(11 * 60_000);
        assertThat(settled(stage)).contains(new CustodyConsume.Abandoned(ExitCallbacks.Abandoned.TIMED_OUT));
        assertThat(r.ledgerOnDisk().records()).as("the decision stands, with no DONE").hasSize(1);
    }

    @Test
    void aStaleReadBackIsNeverTakenForTheProof() throws IOException {
        r.owner.disk = DiskMode.STUCK;
        PlayerDatFiles.save(r.playerdata, r.owner.id, PlayerDatFiles.account(0), 2); // a snapshot from before the exit
        hold(3, item("gem", 5));
        var stage = running(consume.consume(r.owner, Rig.GUI, 3, Reach.SAVED));

        r.sim.advance(20_000);

        assertThat(r.owner.saveTimes).isNotEmpty();
        assertThat(settled(stage)).isEmpty();
        r.owner.disk = DiskMode.WORKS;
        r.sim.advance(60_000);
        assertThat(settled(stage)).as("released by the save that really landed").contains(new CustodyConsume.Consumed(Reach.SAVED));
    }

    @Test
    void shutdownAbandonsAWaitingEffect() {
        r.owner.disk = DiskMode.STUCK;
        hold(3, item("gem", 5));
        var stage = running(consume.consume(r.owner, Rig.GUI, 3, Reach.SAVED));
        r.sim.advance(1_000);

        r.barrier.close();

        assertThat(settled(stage)).contains(new CustodyConsume.Abandoned(ExitCallbacks.Abandoned.SHUTDOWN));
    }

    @Test
    void aPlayerWhoLeavesBeforeTheProofAbandonsTheEffectAndStillHasTheConsumeAppliedByRecovery() {
        r.owner.disk = DiskMode.STUCK;
        hold(3, item("gem", 5));
        var stage = running(consume.consume(r.owner, Rig.GUI, 3, Reach.SAVED));
        r.sim.advance(1_000);

        r.barrier.left(r.owner.id);
        r.owner.online = false;

        assertThat(settled(stage)).contains(new CustodyConsume.Abandoned(ExitCallbacks.Abandoned.PLAYER_LEFT));
        assertThat(recoveredCount("gem")).as("G2: the decision is durable, the stack is never returned").isZero();
    }

    // ---- refusals --------------------------------------------------------------------------------------------------

    @Test
    void anEmptySlotAndASlotNobodyHoldsAreDeclinedAndChangeNothing() {
        long gen = r.rig.escrow.account().gen();

        CustodyConsume.Started<Item> started = consume.consume(r.owner, Rig.GUI, 6, Reach.DECIDED);

        assertThat(started).isInstanceOf(CustodyConsume.Declined.class);
        assertThat(r.rig.escrow.account().gen()).isEqualTo(gen);
        assertThat(r.sim.idle()).as("nothing was queued").isTrue();
        assertThat(r.ledgerOnDisk().records()).isEmpty();
    }

    @Test
    void aSlotThatIsAlreadyExitingIsBusyAndTheSecondConsumeIsDeclined() {
        hold(3, item("gem", 5));
        var first = running(consume.consume(r.owner, Rig.GUI, 3, Reach.DECIDED));

        CustodyConsume.Started<Item> second = consume.consume(r.owner, Rig.GUI, 3, Reach.DECIDED);

        assertThat(second).isInstanceOfSatisfying(CustodyConsume.Declined.class,
                declined -> assertThat(declined.reason()).isEqualTo(Tier1.Reason.BUSY));
        r.sim.runAll();
        assertThat(settled(first)).contains(new CustodyConsume.Consumed(Reach.DECIDED));
        assertThat(r.ledgerOnDisk().records()).as("one exit, not two").hasSize(1);
    }

    @Test
    void aShutGateDeclinesAndTheStackStaysHeld() {
        Rig closed = new Rig(); // a player whose recovery gate never opened
        closed.world.top(3, item("gem", 5));
        Owner<Item> shut = new Owner<>() {
            @Override
            public java.util.UUID id() {
                return r.owner.id();
            }

            @Override
            public PlayerEscrow escrow() {
                return closed.escrow;
            }

            @Override
            public Live<Item> live() {
                return closed.world;
            }

            @Override
            public void run(Runnable task, Runnable retired) {
                r.owner.run(task, retired);
            }

            @Override
            public void runAfter(long millis, Runnable task, Runnable retired) {
                r.owner.runAfter(millis, task, retired);
            }

            @Override
            public void save() {
                throw new AssertionError("a declined consume asks for no save");
            }
        };

        CustodyConsume.Started<Item> started = new CustodyConsume<>(closed.tier1, r.exits)
                .consume(shut, Rig.GUI, 3, Reach.DECIDED);

        assertThat(started).isInstanceOfSatisfying(CustodyConsume.Declined.class,
                declined -> assertThat(declined.reason()).isEqualTo(Tier1.Reason.UNAVAILABLE));
        assertThat(closed.world.top(3)).as("the stack stays where it is").isEqualTo(item("gem", 5));
        assertThat(r.sim.idle()).isTrue();
    }

    @Test
    void aLedgerThatCannotRecordTheDecisionRefusesAndTheStackGoesBack() throws IOException {
        Files.createDirectories(r.escrowDir.getParent());
        Files.writeString(r.escrowDir, "not a directory"); // the ledger cannot be created: the append fails
        hold(3, item("gem", 5));
        var stage = running(consume.consume(r.owner, Rig.GUI, 3, Reach.SAVED));

        r.sim.runAll();

        assertThat(settled(stage)).contains(new CustodyConsume.Refused(Tier1.Reason.UNRECORDABLE));
        assertThat(r.rig.world.bagCount("gem")).as("nothing was consumed: the stack went back").isEqualTo(5);
        assertThat(r.rig.world.top(3)).isNull();
        assertThat(r.rig.escrow.account().entries()).isEmpty();
        assertThat(r.owner.saveTimes).isEmpty();
    }

    // ---- crash boundaries --------------------------------------------------------------------------------------------

    @Test
    void everyKillBoundaryOfAConsumeKeepsTheRulesOfTheDesign() throws IOException {
        int consumedByRecovery = 0;
        int returnedByRecovery = 0;
        for (Reach reach : Reach.values()) {
            for (int kill = 0; kill < 40; kill++) {
                Path dir = Files.createTempDirectory(root, "k");
                try (BarrierRig run = new BarrierRig(dir, 5_000, 600_000)) {
                    CustodyConsume<Item> sweep = new CustodyConsume<>(run.rig.tier1, run.exits);
                    run.rig.world.cursor = item("gem", 5);
                    run.rig.tier1.apply(run.rig.escrow, run.rig.world, Rig.GUI, Rig.SLOTS_0_TO_8,
                            new Delta<Item>().top(3, item("gem", 5)).cursor(null));
                    run.owner.writeSnapshot(); // S': the entry HELD, saved before the exit
                    var started = (CustodyConsume.Running<Item>) sweep.consume(run.owner, Rig.GUI, 3, reach);
                    boolean ended = false;
                    for (int step = 0; step < kill; step++) {
                        if (!run.sim.runOne()) {
                            ended = true;
                            break;
                        }
                    }
                    var future = started.outcome().toCompletableFuture();
                    boolean decided = !run.ledgerOnDisk().records().isEmpty();
                    String where = reach + " kill=" + kill;
                    if (future.isDone() && future.join() instanceof CustodyConsume.Consumed consumed
                            && consumed.reached() == Reach.SAVED) {
                        assertThat(decided).as("%s: the effect ran only after the decision", where).isTrue();
                        assertThat(run.gen()).as("%s: and after the proof", where).isGreaterThanOrEqualTo(1);
                    }
                    int returned = recoveredCount(run, "gem");
                    if (decided) {
                        assertThat(returned).as("%s: G2", where).isZero();
                        consumedByRecovery++;
                    } else {
                        assertThat(returned).as("%s: an undecided consume returns the stack once", where).isEqualTo(5);
                        returnedByRecovery++;
                    }
                    if (ended) {
                        break;
                    }
                }
            }
        }
        assertThat(consumedByRecovery).isGreaterThan(5);
        assertThat(returnedByRecovery).isGreaterThan(0);
    }

    private int recoveredCount(String id) {
        return recoveredCount(r, id);
    }

    private static int recoveredCount(BarrierRig run, String id) {
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
}
