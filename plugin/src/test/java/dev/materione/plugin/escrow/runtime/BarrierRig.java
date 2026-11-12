package dev.materione.plugin.escrow.runtime;

import dev.materione.plugin.escrow.io.EscrowLedgerStore;
import dev.materione.plugin.escrow.io.EscrowReadBack;
import dev.materione.plugin.escrow.io.LedgerOptions;
import dev.materione.plugin.escrow.io.PlayerDatFiles;
import dev.materione.plugin.escrow.model.AccountCodec;
import dev.materione.plugin.escrow.model.AccountRead;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.LedgerCodec;
import dev.materione.plugin.escrow.model.LedgerRead;
import dev.materione.plugin.escrow.runtime.Rig.Item;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A server-less rig for tiers 2 and 3 (gui-input-durability.md §5.4): the real ledger store, the real read-back over a
 * real {@code playerdata} folder, the real barrier and exit runner, driven by one deterministic scheduler. A player is
 * a manual task queue and a clock; "the platform's save" is a method that writes the player's account into a real
 * gzip-NBT file by the server's own replace dance, and can be made to do nothing, throw, or stop mid-way. Every
 * thread hop of the production code is a task in {@link #sim}, so a test can stop between any two of them (a kill).
 */
final class BarrierRig implements AutoCloseable {

    /** One deterministic scheduler and clock: posted tasks run in order, delayed ones when the clock reaches them. */
    static final class Sim {

        final AtomicLong now = new AtomicLong(1_000_000_000_000L);
        private final java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        private final PriorityQueue<Delayed> delayed = new PriorityQueue<>();
        private long sequence;

        private record Delayed(long due, long order, Runnable task) implements Comparable<Delayed> {
            @Override
            public int compareTo(Delayed other) {
                int byDue = Long.compare(due, other.due);
                return byDue != 0 ? byDue : Long.compare(order, other.order);
            }
        }

        void post(Runnable task) {
            queue.add(task);
        }

        void later(long millis, Runnable task) {
            delayed.add(new Delayed(now.get() + millis, sequence++, task));
        }

        boolean idle() {
            return queue.isEmpty();
        }

        boolean nothingScheduled() {
            return queue.isEmpty() && delayed.isEmpty();
        }

        /** Runs one posted task; false when none. */
        boolean runOne() {
            Runnable next = queue.poll();
            if (next == null) {
                return false;
            }
            next.run();
            return true;
        }

        /** Runs posted tasks (and what they post) until none is left. */
        void runAll() {
            while (runOne()) {
                // drain
            }
        }

        /** Moves the clock forward, running every delayed task that falls due on the way. */
        void advance(long millis) {
            long target = now.get() + millis;
            runAll();
            while (!delayed.isEmpty() && delayed.peek().due() <= target) {
                Delayed next = delayed.poll();
                now.set(Math.max(now.get(), next.due()));
                next.task().run();
                runAll();
            }
            now.set(target);
        }
    }

    /** What happens when the platform saves this player. */
    enum DiskMode {
        /** A complete snapshot lands (the server's rename dance). */
        WORKS,
        /** The save silently does nothing (F1: a failed save is logged and swallowed). */
        STUCK,
        /** The save throws. */
        THROWS
    }

    /** The player as the barrier sees them. */
    final class FakeOwner implements Owner<Item> {

        final UUID id = UUID.fromString("00000000-0000-4000-8000-000000000f01");
        boolean online = true;
        DiskMode disk = DiskMode.WORKS;
        final List<Long> saveTimes = new ArrayList<>();

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public PlayerEscrow escrow() {
            return rig.escrow;
        }

        @Override
        public Live<Item> live() {
            return rig.world;
        }

        @Override
        public void run(Runnable task, Runnable retired) {
            sim.post(online ? task : (retired == null ? () -> { } : retired));
        }

        @Override
        public void runAfter(long millis, Runnable task, Runnable retired) {
            sim.later(millis, () -> {
                if (online) {
                    task.run();
                } else if (retired != null) {
                    retired.run();
                }
            });
        }

        @Override
        public void save() {
            saveTimes.add(sim.now.get());
            switch (disk) {
                case THROWS -> throw new IllegalStateException("disk on fire");
                case STUCK -> { }
                case WORKS -> writeSnapshot();
            }
        }

        /** The snapshot the platform would write now: the live account's persisted bytes, in a real file. */
        void writeSnapshot() {
            try {
                byte[] account = rig.written.isEmpty() ? null : rig.written.get(rig.written.size() - 1);
                PlayerDatFiles.save(playerdata, id, account, 4);
            } catch (IOException failed) {
                throw new java.io.UncheckedIOException(failed);
            }
        }
    }

    final Sim sim = new Sim();
    final Rig rig = Rig.open();
    final Path playerdata;
    final Path escrowDir;
    final List<String> log = new ArrayList<>();
    final EscrowLedgerStore store;
    final SaveBarrier barrier;
    final ExitRunner<Item> exits;
    final FakeOwner owner = new FakeOwner();

    BarrierRig(Path root, long cooldownMillis, long giveUpMillis) {
        this.playerdata = root.resolve("world").resolve("playerdata");
        this.escrowDir = root.resolve("storage").resolve("escrow");
        this.store = new EscrowLedgerStore(escrowDir, sim::post, LedgerOptions.DEFAULTS.withFsync(false), sim.now::get,
                diagnostic -> { }, log::add);
        EscrowReadBack reader = new EscrowReadBack(() -> List.of(playerdata), sim::post, PlayerDatFiles.KEY,
                (millis, task) -> sim.later(millis, () -> sim.post(task)));
        this.barrier = new SaveBarrier(store, reader, sim.now::get, cooldownMillis, giveUpMillis, log::add);
        this.exits = new ExitRunner<>(rig.tier1, store, barrier);
    }

    /** What the player's saved file holds, as the barrier reads it: the account's gen, or -1 when none. */
    long gen() {
        AccountRead read = accountOnDisk();
        return read instanceof AccountRead.Readable readable ? readable.account().gen() : -1;
    }

    /** The account value in the player's file, as the server would load it. */
    AccountRead accountOnDisk() {
        try {
            var value = dev.materione.plugin.escrow.io.PlayerDatReader.readValue(
                    playerdata.resolve(owner.id + ".dat"), PlayerDatFiles.KEY);
            return AccountCodec.read(value.orElse(null));
        } catch (IOException none) {
            return AccountCodec.read(null);
        }
    }

    /** The ledger as the file holds it. */
    Ledger ledgerOnDisk() {
        try {
            Path file = store.fileOf(owner.id);
            if (!Files.exists(file)) {
                return Ledger.EMPTY;
            }
            return ((LedgerRead.Valid) LedgerCodec.read(Files.readAllBytes(file))).ledger();
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    @Override
    public void close() {
        store.close();
    }
}
