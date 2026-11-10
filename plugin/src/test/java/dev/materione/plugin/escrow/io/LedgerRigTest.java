package dev.materione.plugin.escrow.io;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.plugin.escrow.model.ExitKind;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.LedgerCodec;
import dev.materione.plugin.escrow.model.LedgerRead;
import dev.materione.plugin.escrow.model.LedgerRecord;
import dev.materione.plugin.escrow.model.StackBytes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Test rig for the ledger IO: a store over a temp directory, a real or a manual executor, a settable clock. */
final class LedgerRigTest implements AutoCloseable {

    /** An executor that runs nothing until the test says so (proves what the caller's thread does, and orders). */
    static final class ManualExecutor implements java.util.concurrent.Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable task) {
            tasks.add(task);
        }

        synchronized int pending() {
            return tasks.size();
        }

        /** Runs queued tasks (and what they queue) on the calling thread until none is left. */
        void runAll() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    next = tasks.poll();
                }
                if (next == null) {
                    return;
                }
                next.run();
            }
        }
    }

    final Path dir;
    final AtomicLong now = new AtomicLong(1_000_000_000_000L);
    final List<Diagnostic> diagnostics = new CopyOnWriteArrayList<>();
    final List<String> log = new CopyOnWriteArrayList<>();
    final EscrowLedgerStore.Hooks hooks = new EscrowLedgerStore.Hooks();
    final EscrowLedgerStore store;
    private final ExecutorService pool;

    /** Over a real pool of {@code threads} named {@code escrow-io-N}. */
    LedgerRigTest(Path escrowDir, int threads, LedgerOptions options) {
        this.dir = escrowDir;
        this.pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "escrow-io-test");
            thread.setDaemon(true);
            return thread;
        });
        this.store = new EscrowLedgerStore(escrowDir, pool, options, now::get, diagnostics::add, log::add, hooks);
    }

    /** Over a caller-supplied executor. */
    LedgerRigTest(Path escrowDir, java.util.concurrent.Executor executor, LedgerOptions options) {
        this.dir = escrowDir;
        this.pool = null;
        this.store = new EscrowLedgerStore(escrowDir, executor, options, now::get, diagnostics::add, log::add, hooks);
    }

    static Id128 id(long n) {
        return new Id128(0x1111222233334444L, n);
    }

    static LedgerRecord.Intent intent(long n, long atMillis) {
        return new LedgerRecord.Intent(id(n), id(1000 + n), ExitKind.CONSUME,
                StackBytes.of(("stack" + n).getBytes(StandardCharsets.UTF_8)).hash16(), 1 + n, atMillis);
    }

    static LedgerRecord.Done done(long n) {
        return new LedgerRecord.Done(id(n));
    }

    static UUID player(int n) {
        return new UUID(0xAAAAL, n);
    }

    Path file(UUID player) {
        return store.fileOf(player);
    }

    /** What is on disk now, as the codec reads it. */
    LedgerRead.Valid onDisk(UUID player) throws IOException {
        return (LedgerRead.Valid) LedgerCodec.read(
                Files.exists(file(player)) ? Files.readAllBytes(file(player)) : new byte[0]);
    }

    Ledger ledgerOnDisk(UUID player) throws IOException {
        return onDisk(player).ledger();
    }

    static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    @Override
    public void close() {
        store.close();
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
