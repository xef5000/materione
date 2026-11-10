package dev.materione.plugin.escrow.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * K23 as a property (gui-input-durability.md §5.10): random sequences of exits, saves, kills of the read-back, stale
 * and failed read-backs, clock jumps and expiries, run against {@link BarrierBook} with a model disk that holds the
 * snapshots a real server would (the live {@code gen} at each save). The oracle:
 *
 * <ul>
 *   <li><b>No false acknowledgment.</b> An exit is acknowledged only by a read-back of a snapshot that was saved
 *       <em>after</em> the exit's step 1 (a save that predates it holds a smaller {@code gen}), so "stale", "empty",
 *       "unreadable" and "the save never landed" never release an effect.
 *   <li><b>At most once.</b> No exit is acknowledged twice, and an acknowledged exit is no longer waiting.
 *   <li><b>D8's narrowness.</b> A save is requested only while an exit that carries an effect is waiting; exits with no
 *       effect are acknowledged by snapshots the server saved by itself.
 *   <li><b>The rate limit.</b> Two saves are never closer than the cooldown in force, whatever the caller tries.
 *   <li><b>Progress.</b> When the disk finally works (a save after the last exit, read back whole), every exit still
 *       waiting is acknowledged.
 * </ul>
 */
class BarrierBookPropertyTest {

    private record Snapshot(long seq, long gen) {}

    private static final class Run {

        final Random random;
        final BarrierBook book = new BarrierBook(5_000, 600_000);
        final List<Snapshot> disk = new ArrayList<>();
        final Map<Id128, Long> registeredAtSeq = new HashMap<>();
        final Set<Id128> acked = new HashSet<>();
        final Set<Id128> effects = new HashSet<>();
        long now = 1_000_000;
        long live = 0;
        long seq = 0;
        long lastSaveAt = Long.MIN_VALUE;
        long nextTxn = 1;
        int saves;
        int acks;
        boolean reliable;
        int expired;

        Run(long seed) {
            this.random = new Random(seed);
        }

        void register() {
            live++;
            LedgerRecord.Intent intent = new LedgerRecord.Intent(new Id128(9, nextTxn), new Id128(8, nextTxn),
                    ExitKind.CONSUME, new Id128(1, 1), 1, now);
            nextTxn++;
            boolean effect = random.nextInt(3) != 0;
            book.register(intent, live, effect, now);
            if (effect) {
                effects.add(intent.txn());
            }
            registeredAtSeq.put(intent.txn(), seq);
            seq++;
        }

        void trySave() {
            if (book.decide(now) instanceof BarrierBook.Save) {
                long cooldown = book.currentCooldown();
                if (lastSaveAt != Long.MIN_VALUE) {
                    assertThat(now - lastSaveAt).as("two saves closer than the cooldown").isGreaterThanOrEqualTo(cooldown);
                }
                assertThat(book.waiting()).as("D8: a save is requested only for an exit that carries an effect")
                        .anyMatch(BarrierBook.Pending::carriesEffect);
                book.saved(now, live);
                lastSaveAt = now;
                saves++;
                seq++;
                if (reliable || random.nextInt(4) != 0) { // one save in four silently does not land
                    disk.add(new Snapshot(seq, live));
                }
                readBack(reliable ? 2 : random.nextInt(6));
            } else if (book.decide(now) instanceof BarrierBook.Poll) {
                book.polled(now);
                seq++;
                readBack(!reliable && random.nextInt(3) == 0 ? 0 : 2); // whatever the server's own saves left on disk
            } else if (book.inFlight() || book.decide(now) instanceof BarrierBook.Wait) {
                try {
                    book.saved(now, live);
                    throw new AssertionError("the book accepted a save it had not allowed");
                } catch (IllegalStateException refused) {
                    // the limit holds against a caller that does not ask first
                }
            }
        }

        /** The read-back after a save: 0 = unreadable, 1 = an older snapshot, otherwise the newest. */
        void readBack(int how) {
            if (!book.inFlight()) {
                return;
            }
            OptionalLong read;
            Snapshot used = null;
            if (disk.isEmpty() || how == 0) {
                read = OptionalLong.empty();
            } else if (how == 1) {
                used = disk.get(random.nextInt(disk.size()));
                read = OptionalLong.of(used.gen());
            } else {
                used = disk.get(disk.size() - 1);
                read = OptionalLong.of(used.gen());
            }
            for (BarrierBook.Acked exit : book.readBack(read)) {
                Id128 txn = exit.exit().txn();
                assertThat(acked.add(txn)).as("acknowledged twice: %s", txn).isTrue();
                assertThat(used).as("acknowledged without a snapshot").isNotNull();
                assertThat(used.seq()).as("acknowledged by a snapshot saved before its step 1")
                        .isGreaterThan(registeredAtSeq.get(txn));
                assertThat(used.gen()).isGreaterThanOrEqualTo(exit.exit().exitGen());
                assertThat(book.isWaiting(txn)).isFalse();
                acks++;
            }
        }
    }

    @Test
    void noSequenceOfSavesAndReadBacksAcknowledgesWhatNoSnapshotProves() {
        int totalSaves = 0;
        int totalAcks = 0;
        int totalExpired = 0;
        for (long seed = 1; seed <= 400; seed++) {
            Run run = new Run(seed);
            for (int step = 0; step < 80; step++) {
                switch (run.random.nextInt(9)) {
                    case 0, 1 -> run.register();
                    case 2, 3, 4 -> run.trySave();
                    case 5 -> run.now += run.random.nextInt(12_000);
                    case 6 -> run.now += run.random.nextInt(400_000);
                    case 7 -> run.disk.add(new Snapshot(++run.seq, run.live)); // the server's own autosave
                    default -> {
                        int before = run.book.waiting().size();
                        int gone = run.book.expire(run.now).size();
                        assertThat(run.book.waiting()).hasSize(before - gone);
                        run.expired += gone;
                    }
                }
            }
            // progress: the disk works from here on
            run.now += 600_000;
            run.reliable = true;
            if (run.book.inFlight()) {
                run.readBack(2);
            }
            run.now += 400_000;
            run.live++; // some later step spent a gen; the save below covers every waiting exit
            run.disk.clear();
            run.disk.add(new Snapshot(++run.seq, run.live)); // an autosave lands: what polling finds
            run.now += 100_000;
            if (!run.book.waiting().isEmpty()) {
                run.trySave();
                if (run.book.inFlight()) {
                    run.readBack(2);
                }
                assertThat(run.book.waiting()).as("seed %d: the working disk acknowledged everything", seed).isEmpty();
            }
            totalSaves += run.saves;
            totalAcks += run.acks;
            totalExpired += run.expired;
        }
        assertThat(totalSaves).as("the run exercised saves").isGreaterThan(2_500);
        assertThat(totalAcks).as("and acknowledgments").isGreaterThan(1_000);
        assertThat(totalExpired).as("and give-ups").isGreaterThan(0);
    }
}
