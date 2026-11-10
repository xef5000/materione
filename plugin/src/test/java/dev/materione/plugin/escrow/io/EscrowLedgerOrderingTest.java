package dev.materione.plugin.escrow.io;

import static dev.materione.plugin.escrow.io.LedgerRigTest.done;
import static dev.materione.plugin.escrow.io.LedgerRigTest.id;
import static dev.materione.plugin.escrow.io.LedgerRigTest.intent;
import static dev.materione.plugin.escrow.io.LedgerRigTest.player;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.LedgerRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Row L: the writer's ordering and threading contract (gui-input-durability.md §5.4 step 2, §5.6, §5.7): FIFO per
 * player, parallel across players, and nothing at all on the caller's thread.
 */
class EscrowLedgerOrderingTest {

    @TempDir
    Path tmp;

    @Test
    void aPlayersRecordsReachTheFileInCallOrderWhateverThePoolDoes() throws Exception {
        try (LedgerRigTest rig = new LedgerRigTest(tmp.resolve("escrow"), 8, LedgerOptions.DEFAULTS.withFsync(false))) {
            int players = 6;
            int perPlayer = 60;
            List<CompletableFuture<AppendResult>> all = new ArrayList<>();
            // one thread submits round-robin: call order per player is submission order
            for (int n = 0; n < perPlayer; n++) {
                for (int p = 0; p < players; p++) {
                    all.add(rig.store.append(player(p), n % 2 == 0 ? intent(n, 5L) : done(n - 1)));
                }
            }
            for (CompletableFuture<AppendResult> future : all) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isInstanceOf(AppendResult.Durable.class);
            }
            for (int p = 0; p < players; p++) {
                List<LedgerRecord> records = rig.ledgerOnDisk(player(p)).records();
                assertThat(records).hasSize(perPlayer);
                for (int n = 0; n < perPlayer; n++) {
                    assertThat(records.get(n)).isEqualTo(n % 2 == 0 ? intent(n, 5L) : done(n - 1));
                }
            }
        }
    }

    @Test
    void appendsFromManyThreadsKeepEachCallersOrderAndNeverOverlapForOnePlayer() throws Exception {
        try (LedgerRigTest rig = new LedgerRigTest(tmp.resolve("escrow"), 8, LedgerOptions.DEFAULTS.withFsync(false))) {
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger maxInFlight = new AtomicInteger();
            rig.hooks.beforeAppend = who -> {
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                Thread.yield();
                inFlight.decrementAndGet();
            };
            UUID only = player(1);
            List<Thread> threads = new ArrayList<>();
            List<CompletableFuture<AppendResult>> futures = new java.util.concurrent.CopyOnWriteArrayList<>();
            for (int t = 0; t < 4; t++) {
                int base = t * 50;
                Thread thread = new Thread(() -> {
                    for (int n = 0; n < 50; n++) {
                        futures.add(rig.store.append(only, intent(base + n, 1L)));
                    }
                });
                threads.add(thread);
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join();
            }
            for (CompletableFuture<AppendResult> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            assertThat(maxInFlight.get()).as("one player's appends are serialised").isEqualTo(1);
            // each caller's records are in its own order
            List<LedgerRecord> records = rig.ledgerOnDisk(only).records();
            assertThat(records).hasSize(200);
            for (int t = 0; t < 4; t++) {
                int base = t * 50;
                List<Long> mine = new ArrayList<>();
                for (LedgerRecord record : records) {
                    long n = ((LedgerRecord.Intent) record).atMillis() == 1L ? txnNumber(record) : -1;
                    if (n >= base && n < base + 50) {
                        mine.add(n);
                    }
                }
                assertThat(mine).isSorted().hasSize(50);
            }
        }
    }

    private static long txnNumber(LedgerRecord record) {
        Id128 txn = record.txn();
        return txn.lo();
    }

    @Test
    void differentPlayersWriteAtTheSameTime() throws Exception {
        try (LedgerRigTest rig = new LedgerRigTest(tmp.resolve("escrow"), 4, LedgerOptions.DEFAULTS)) {
            CyclicBarrier meeting = new CyclicBarrier(2);
            ConcurrentMap<UUID, Boolean> met = new ConcurrentHashMap<>();
            // an append only gets past the hook when a second player's append is inside it too: a single writer
            // thread for everybody would time the barrier out
            rig.hooks.beforeAppend = who -> {
                try {
                    meeting.await(5, TimeUnit.SECONDS);
                    met.put(who, true);
                } catch (Exception timedOut) {
                    throw new IllegalStateException("no second player was writing", timedOut);
                }
            };
            CompletableFuture<AppendResult> a = rig.store.append(player(1), intent(1, 1L));
            CompletableFuture<AppendResult> b = rig.store.append(player(2), intent(2, 1L));
            assertThat(a.get(10, TimeUnit.SECONDS)).isInstanceOf(AppendResult.Durable.class);
            assertThat(b.get(10, TimeUnit.SECONDS)).isInstanceOf(AppendResult.Durable.class);
            assertThat(met).containsOnlyKeys(player(1), player(2));
        }
    }

    @Test
    void theCallersThreadDoesNoIoAndTheWorkRunsOnTheExecutor() throws Exception {
        LedgerRigTest.ManualExecutor executor = new LedgerRigTest.ManualExecutor();
        Path escrow = tmp.resolve("escrow");
        try (LedgerRigTest rig = new LedgerRigTest(escrow, executor, LedgerOptions.DEFAULTS)) {
            UUID who = player(1);
            LedgerRigTest.write(rig.file(who), dev.materione.plugin.escrow.model.LedgerCodec.encode(
                    new dev.materione.plugin.escrow.model.Ledger(List.of(intent(1, 1L)))));
            Thread runner = new Thread(executor::runAll, "the-executor");
            List<Thread> ioThreads = new java.util.concurrent.CopyOnWriteArrayList<>();
            rig.hooks.beforeAppend = ignored -> ioThreads.add(Thread.currentThread());
            CompletableFuture<LedgerState> loaded = rig.store.preload(who);
            CompletableFuture<AppendResult> appended = rig.store.append(who, intent(2, 1L));
            rig.store.release(who);
            assertThat(rig.store.preloaded(who)).as("a lookup is memory only").isEmpty();
            assertThat(loaded).isNotDone();
            assertThat(appended).isNotDone();
            assertThat(Files.size(rig.file(who))).as("nothing was read or written yet").isEqualTo(
                    dev.materione.plugin.escrow.model.LedgerCodec.encode(
                            new dev.materione.plugin.escrow.model.Ledger(List.of(intent(1, 1L)))).length);
            runner.start();
            runner.join();
            assertThat(loaded.get(5, TimeUnit.SECONDS)).isInstanceOf(LedgerState.Ready.class);
            assertThat(appended.get(5, TimeUnit.SECONDS)).isInstanceOf(AppendResult.Durable.class);
            assertThat(ioThreads).hasSize(1).allSatisfy(thread -> assertThat(thread.getName()).isEqualTo("the-executor"));
        }
    }

    @Test
    void aRejoinsPreloadWaitsForItsOwnInFlightIntent() throws Exception {
        LedgerRigTest.ManualExecutor executor = new LedgerRigTest.ManualExecutor();
        try (LedgerRigTest rig = new LedgerRigTest(tmp.resolve("escrow"), executor, LedgerOptions.DEFAULTS)) {
            UUID who = player(1);
            // K14: the player quits before the INTENT's ack and rejoins at once
            CompletableFuture<AppendResult> appended = rig.store.append(who, intent(1, 1L));
            rig.store.release(who);
            CompletableFuture<LedgerState> rejoin = rig.store.preload(who);
            executor.runAll();
            assertThat(appended.get(5, TimeUnit.SECONDS)).isInstanceOf(AppendResult.Durable.class);
            LedgerState.Ready ready = (LedgerState.Ready) rejoin.get(5, TimeUnit.SECONDS);
            assertThat(ready.ledger().hasIntent(id(1001))).as("the preload saw the INTENT queued before it").isTrue();
            assertThat(rig.store.preloaded(who)).as("the rejoin kept its image: the release did not retire it")
                    .isPresent();
        }
    }

    @Test
    void releaseDropsTheImageAndAnIdleOneIsSweptByTheNextPreload() throws Exception {
        try (LedgerRigTest rig = new LedgerRigTest(tmp.resolve("escrow"), 2, LedgerOptions.DEFAULTS)) {
            rig.store.preload(player(1)).get(5, TimeUnit.SECONDS);
            rig.store.preload(player(2)).get(5, TimeUnit.SECONDS);
            assertThat(rig.store.preloaded(player(1))).isPresent();
            rig.store.release(player(1));
            // the release is queued behind the player's work: wait for it through a fresh unrelated round trip
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (rig.store.preloaded(player(1)).isPresent() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(rig.store.preloaded(player(1))).isEmpty();
            assertThat(rig.store.preloaded(player(2))).isPresent();
            rig.now.addAndGet(EscrowLedgerStore.IDLE_EVICT_MILLIS + 1);
            rig.store.preload(player(3)).get(5, TimeUnit.SECONDS);
            assertThat(rig.store.preloaded(player(2))).as("never touched for the idle window").isEmpty();
            assertThat(rig.store.preloaded(player(3))).isPresent();
        }
    }

    @Test
    void awaitPreloadGivesUpAtTheTimeoutAndTheStateFillsInLater() throws Exception {
        LedgerRigTest.ManualExecutor executor = new LedgerRigTest.ManualExecutor();
        try (LedgerRigTest rig = new LedgerRigTest(tmp.resolve("escrow"), executor, LedgerOptions.DEFAULTS)) {
            UUID who = player(1);
            assertThat(rig.store.awaitPreload(who, Duration.ofMillis(30))).isEmpty();
            assertThat(rig.store.preloaded(who)).isEmpty();
            executor.runAll();
            assertThat(rig.store.preloaded(who)).containsInstanceOf(LedgerState.Ready.class);
        }
    }

    @Test
    void closeDrainsQueuedWorkOnTheCallingThreadAndRefusesLaterWork() throws Exception {
        LedgerRigTest.ManualExecutor executor = new LedgerRigTest.ManualExecutor();
        LedgerRigTest rig = new LedgerRigTest(tmp.resolve("escrow"), executor, LedgerOptions.DEFAULTS);
        UUID who = player(1);
        CompletableFuture<AppendResult> inFlight = rig.store.append(who, intent(1, 1L));
        assertThat(inFlight).isNotDone();
        rig.store.close(); // plugin disable: the async scheduler is about to drop its tasks
        assertThat(inFlight.get(5, TimeUnit.SECONDS)).isInstanceOf(AppendResult.Durable.class);
        assertThat(rig.ledgerOnDisk(who).hasIntent(id(1001))).isTrue();
        AppendResult late = rig.store.append(who, intent(2, 1L)).get(5, TimeUnit.SECONDS);
        assertThat(late).isInstanceOfSatisfying(AppendResult.Refused.class,
                refused -> assertThat(refused.reason()).isEqualTo(AppendResult.Reason.SHUTDOWN));
        assertThat(rig.ledgerOnDisk(who).records()).hasSize(1);
    }
}
