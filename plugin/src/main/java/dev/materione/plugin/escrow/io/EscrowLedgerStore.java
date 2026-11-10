package dev.materione.plugin.escrow.io;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.plugin.escrow.model.Compaction;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.LedgerCodec;
import dev.materione.plugin.escrow.model.LedgerRead;
import dev.materione.plugin.escrow.model.LedgerRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The IO half of the escrow ledger (gui-input-durability.md §5.3, §5.4 tier 2, §5.6, §5.7; #247 row L): one
 * append-only file per player, {@code storage/escrow/<uuid>.ledger}, written and read only on the plugin's IO
 * executor ({@code SchedulingGateway.executeAsync}, plugin.md §2), never on a region, entity or main thread (D8, I4).
 *
 * <h2>Ordering and concurrency</h2>
 *
 * Every operation for one player (preload, append, compaction, release) goes through that player's FIFO queue, and a
 * queue is drained by at most one IO task at a time, so a player's records reach the file in call order and a
 * rejoin's preload waits behind its own in-flight appends (K14). Different players' queues are independent and run
 * in parallel on the executor. Nothing here blocks an executor thread on another player, and no operation blocks its
 * caller: each returns a {@link CompletableFuture} completed on an IO thread. A caller on an owner thread attaches a
 * continuation that hops back through the scheduling gateway ({@code executeEntity}); it never calls
 * {@code get()} or {@code join()}. The only blocking call is {@link #awaitPreload}, for the already-asynchronous
 * {@code AsyncPlayerPreLoginEvent} (F9).
 *
 * <h2>What the writer does</h2>
 *
 * <ul>
 *   <li><b>Preload</b> ({@link #preload}): reads and validates the file into an in-memory {@link LedgerState}, so the
 *       join handler needs no IO ({@link #preloaded}). A player with no file has an empty ledger and nothing is
 *       created. Compaction runs here when something is droppable.
 *   <li><b>Append</b> ({@link #append}): one {@code FileChannel.write} in append mode, then {@code force(false)} when
 *       {@link LedgerOptions#fsync()} is on; the future completes after both. The first append after a load cuts a
 *       torn tail (K17) and writes the header of a new file. A failed write is {@code MTR-0102}.
 *   <li><b>Corruption</b> (K18): a bad record before the end renames the file to {@code <uuid>.ledger.corrupt-<millis>}
 *       (never deleted), reports {@code MTR-0101} and marks the player {@link LedgerState.Unrecoverable}; a newer
 *       version is left untouched and marked the same. A quarantine file that is still beside the ledger keeps the
 *       player unrecoverable across restarts: the admin resolves it by moving that file away.
 *   <li><b>Compaction</b> ({@link #compact}): {@link Compaction#plan} decides, the rewrite goes to a temporary sibling
 *       and is renamed over the ledger atomically, so a kill at any instant leaves the old file whole or the new one.
 * </ul>
 *
 * <p>The in-memory image is a join-time cache: {@link #release} drops it (the quit handler does), and an image that
 * nobody touched for {@value #IDLE_EVICT_MILLIS} ms is dropped by the next preload. Anything dropped is re-derived
 * from the file the next time it is needed, deterministically.
 */
public final class EscrowLedgerStore implements AutoCloseable {

    /** How long {@link #awaitPreload} callers wait before the gate stays closed (§5.7). */
    public static final Duration PRELOAD_TIMEOUT = Duration.ofSeconds(5);

    /** The directory of the ledgers, below {@code storage/}. */
    public static final String DIRECTORY = "escrow";

    /** A ledger image untouched for this long is dropped by the next preload. */
    static final long IDLE_EVICT_MILLIS = 5 * 60_000L;

    private static final int BATCH = 16;
    private static final long REPORT_EVERY_MILLIS = 60_000L;

    private final Path dir;
    private final Executor io;
    private final LedgerOptions options;
    private final LongSupplier clock;
    private final Consumer<Diagnostic> diagnostics;
    private final Consumer<String> log;
    private final Hooks hooks;
    private final ConcurrentMap<UUID, Slot> slots = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> reported = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * A store over {@code escrowDir} (normally {@code storage/escrow/}, created on the first append).
     *
     * @param io          where all file work runs: {@code task -> gateway.executeAsync(plugin, task)}
     * @param clock       epoch millis (retention, quarantine names, report rate limits)
     * @param diagnostics receives {@code MTR-0101} and {@code MTR-0102}
     * @param log         receives a line for a failed compaction (harmless; the file is whole)
     */
    public EscrowLedgerStore(Path escrowDir, Executor io, LedgerOptions options, LongSupplier clock,
            Consumer<Diagnostic> diagnostics, Consumer<String> log) {
        this(escrowDir, io, options, clock, diagnostics, log, Hooks.NONE);
    }

    EscrowLedgerStore(Path escrowDir, Executor io, LedgerOptions options, LongSupplier clock,
            Consumer<Diagnostic> diagnostics, Consumer<String> log, Hooks hooks) {
        this.dir = escrowDir;
        this.io = io;
        this.options = options;
        this.clock = clock;
        this.diagnostics = diagnostics;
        this.log = log;
        this.hooks = hooks;
    }

    /** The file of a player's ledger. */
    public Path fileOf(UUID player) {
        return dir.resolve(player + ".ledger");
    }

    // ---- the API rows P and B call ------------------------------------------------------------------------------

    /**
     * Loads (or returns the already-loaded) ledger state of {@code player} on the IO executor, behind every earlier
     * operation for that player. Idempotent. Never fails: a problem is a {@link LedgerState.Unrecoverable}. A caller
     * that needs the state for a player who is already online (the plugin was enabled or reloaded after that player's
     * pre-login) calls this instead of relying on the listener.
     */
    public CompletableFuture<LedgerState> preload(UUID player) {
        sweepIdle();
        return enqueue(player, slot -> {
            ensureLoaded(slot);
            compactIfDue(slot);
            return slot.state;
        }, () -> new LedgerState.Unrecoverable(LedgerState.Why.IO_ERROR, fileOf(player).toString(), "shut down"));
    }

    /**
     * The state a finished preload (or a later append) left in memory: no IO, no queue, safe on any thread, including
     * the join handler. Empty when the preload has not finished, or the image was dropped: the recovery gate then
     * stays closed (§5.7).
     */
    public Optional<LedgerState> preloaded(UUID player) {
        Slot slot = slots.get(player);
        return slot == null ? Optional.empty() : Optional.ofNullable(slot.state);
    }

    /**
     * {@link #preload} and wait, for {@code AsyncPlayerPreLoginEvent} only (F9): that handler already runs off the
     * main thread. Empty on timeout; the work continues and {@link #preloaded} fills in when it finishes. Must not be
     * called from a region, entity or main thread (a ban test lets only the listener call it).
     */
    public Optional<LedgerState> awaitPreload(UUID player, Duration timeout) {
        // a copy times out on its own: completing the queue's own future with null would hand null to its other waiters
        CompletableFuture<LedgerState> waiting =
                preload(player).copy().completeOnTimeout(null, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            return Optional.ofNullable(waiting.join());
        } catch (CompletionException failed) {
            return Optional.empty();
        }
    }

    /**
     * Appends one record ({@code INTENT} at tier 2 step 2, {@code DONE} at step 4) behind every earlier operation for
     * the player. The future completes after the write (and the force) returned: {@link AppendResult.Durable} is the
     * decision point. The player's ledger is loaded first when nobody preloaded it.
     */
    public CompletableFuture<AppendResult> append(UUID player, LedgerRecord record) {
        return enqueue(player, slot -> appendNow(slot, record),
                () -> new AppendResult.Refused(AppendResult.Reason.SHUTDOWN, "the escrow ledger is closed"));
    }

    /** Compacts the player's ledger now (also done by {@link #preload} when something is droppable). */
    public CompletableFuture<CompactResult> compact(UUID player) {
        return enqueue(player, slot -> {
            ensureLoaded(slot);
            return compactNow(slot);
        }, () -> CompactResult.skipped("the escrow ledger is closed"));
    }

    /**
     * Drops the player's in-memory image once everything queued for them has run (call on quit). A rejoin's preload
     * queued before the release runs keeps the image alive.
     */
    public void release(UUID player) {
        Slot slot = slots.get(player);
        if (slot == null || closed) {
            return;
        }
        Runnable retire = () -> {
            synchronized (slot) {
                if (slot.queue.isEmpty()) {
                    slot.retired = true;
                    slots.remove(player, slot);
                }
            }
        };
        if (slot.offer(retire, clock.getAsLong())) {
            schedule(slot);
        }
    }

    /**
     * Stops accepting work and drains what is queued, on the calling thread (plugin disable: the async scheduler is
     * about to cancel its tasks, and an {@code INTENT} in flight must reach the file). Shutdown only. It waits for a
     * drain that is running (one batch of file IO per player, so bounded in practice) and takes over a drain task
     * that is queued but never started.
     */
    @Override
    public void close() {
        closed = true;
        for (Slot slot : slots.values()) {
            slot.drainLock.lock(); // the lock is what keeps a player's drainers one at a time
            try {
                Runnable task;
                while ((task = slot.queue.poll()) != null) {
                    run(task);
                }
            } finally {
                slot.drainLock.unlock();
            }
        }
    }

    // ---- queueing -----------------------------------------------------------------------------------------------

    private <T> CompletableFuture<T> enqueue(UUID player, Function<Slot, T> operation, Supplier<T> whenClosed) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.complete(whenClosed.get());
            return future;
        }
        while (true) {
            Slot slot = slots.computeIfAbsent(player, id -> new Slot(id, fileOf(id)));
            Runnable task = () -> {
                try {
                    future.complete(operation.apply(slot));
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            };
            if (slot.offer(task, clock.getAsLong())) {
                schedule(slot);
                return future;
            }
            // the slot was retired between the lookup and the offer: it is gone from the map, take a fresh one
        }
    }

    private void schedule(Slot slot) {
        if (slot.scheduled.compareAndSet(false, true)) {
            try {
                io.execute(() -> drain(slot));
            } catch (RejectedExecutionException shuttingDown) {
                slot.scheduled.set(false); // the queue stays; close() drains it
            }
        }
    }

    private void drain(Slot slot) {
        try {
            slot.drainLock.lock();
            try {
                for (int ran = 0; ran < BATCH; ran++) {
                    Runnable task = slot.queue.poll();
                    if (task == null) {
                        break;
                    }
                    run(task);
                }
            } finally {
                slot.drainLock.unlock();
            }
        } finally {
            slot.scheduled.set(false);
            if (!slot.queue.isEmpty() && !closed) {
                schedule(slot); // more queued, or an offer raced with the loop above
            }
        }
    }

    private void run(Runnable task) {
        try {
            task.run();
        } catch (Throwable failure) {
            log.accept("escrow ledger task failed: " + failure);
        }
    }

    private void sweepIdle() {
        long now = clock.getAsLong();
        for (Slot slot : slots.values()) {
            if (now - slot.lastUse > IDLE_EVICT_MILLIS && !slot.scheduled.get()) {
                synchronized (slot) {
                    if (slot.queue.isEmpty() && !slot.scheduled.get()) {
                        slot.retired = true;
                        slots.remove(slot.player, slot);
                    }
                }
            }
        }
    }

    // ---- the work, on an IO thread, one task at a time per player -----------------------------------------------

    private void ensureLoaded(Slot slot) {
        if (slot.loaded) {
            return;
        }
        Path file = slot.file;
        try {
            String quarantined = LedgerFiles.findQuarantine(file);
            if (quarantined != null) {
                settle(slot, new LedgerState.Unrecoverable(LedgerState.Why.QUARANTINED, quarantined,
                        "an earlier quarantine is unresolved: move " + quarantined + " away once the ledger is resolved"),
                        true);
                return;
            }
            byte[] bytes = LedgerFiles.read(file);
            switch (LedgerCodec.read(bytes)) {
                case LedgerRead.Valid valid -> {
                    slot.validLength = valid.validLength();
                    slot.fileLength = bytes.length;
                    slot.state = new LedgerState.Ready(valid.ledger());
                    slot.loaded = true;
                }
                case LedgerRead.Newer newer -> settle(slot, new LedgerState.Unrecoverable(
                        LedgerState.Why.NEWER_VERSION, file.toString(),
                        "ledger version " + newer.version() + " is newer than this build reads"), true);
                case LedgerRead.Corrupt corrupt -> quarantine(slot, corrupt);
            }
        } catch (IOException failed) {
            settle(slot, new LedgerState.Unrecoverable(LedgerState.Why.IO_ERROR, file.toString(),
                    "could not be read: " + failed), false);
        }
    }

    private void quarantine(Slot slot, LedgerRead.Corrupt corrupt) {
        String where = slot.file.toString();
        String detail = "corrupt at byte " + corrupt.offset() + ": " + corrupt.why();
        try {
            Path aside = LedgerFiles.quarantine(slot.file, clock.getAsLong());
            where = aside.toString();
            detail += "; renamed aside";
        } catch (IOException cannotMove) {
            detail += "; and it could not be renamed aside (" + cannotMove + ")";
        }
        settle(slot, new LedgerState.Unrecoverable(LedgerState.Why.CORRUPT, where, detail), true);
    }

    /** Records an unrecoverable state, reports it, and keeps it for the load ({@code final}) or retries next time. */
    private void settle(Slot slot, LedgerState.Unrecoverable state, boolean definitive) {
        slot.state = state;
        slot.loaded = definitive;
        report(slot.player, DiagnosticCodes.ESCROW_STATE_UNREADABLE,
                "escrow ledger of " + slot.player + " is unreadable (" + state.why() + "): " + state.detail()
                        + " [" + state.file() + "]; its strict input transfers are refused and nothing was returned or deleted",
                "resolve it by hand: the file is preserved; once it is dealt with, move any .corrupt-* file out of "
                        + dir);
    }

    private AppendResult appendNow(Slot slot, LedgerRecord record) {
        for (int attempt = 0; ; attempt++) {
            ensureLoaded(slot);
            if (slot.state instanceof LedgerState.Unrecoverable unrecoverable) {
                return new AppendResult.Refused(AppendResult.Reason.UNRECOVERABLE,
                        unrecoverable.why() + ": " + unrecoverable.detail());
            }
            Ledger current = ((LedgerState.Ready) slot.state).ledger();
            try {
                hooks.beforeAppend.accept(slot.player);
                byte[] header = slot.validLength == 0 ? LedgerCodec.header() : null;
                slot.fileLength = LedgerFiles.append(slot.file, slot.fileLength, slot.validLength, header,
                        LedgerCodec.encodeRecord(record), options.fsync(), hooks.afterWrite, hooks.afterForce);
                slot.validLength = (int) slot.fileLength;
                Ledger next = current.appended(record);
                slot.state = new LedgerState.Ready(next);
                return new AppendResult.Durable(next);
            } catch (LedgerFiles.StaleView changed) {
                slot.loaded = false; // read the file again, once, and decide on what is really there
                if (attempt == 0) {
                    continue;
                }
                return refuseWrite(slot, changed);
            } catch (IOException | RuntimeException failed) {
                slot.loaded = false; // the file may now end in a partial record: the next call re-reads and cuts it
                return refuseWrite(slot, failed);
            }
        }
    }

    private AppendResult refuseWrite(Slot slot, Exception failed) {
        String detail = "the ledger append failed: " + failed;
        report(slot.player, DiagnosticCodes.ESCROW_TRANSFER_REFUSED,
                "a strict input transfer of " + slot.player + " was refused: " + detail + " [" + slot.file + "]",
                "check that " + dir + " is writable and the disk is not full; the click did nothing");
        return new AppendResult.Refused(AppendResult.Reason.WRITE_FAILED, detail);
    }

    private void compactIfDue(Slot slot) {
        if (slot.state instanceof LedgerState.Ready ready
                && Compaction.plan(ready.ledger(), clock.getAsLong(), options.retentionMillis()) != ready.ledger()) {
            compactNow(slot);
        }
    }

    private CompactResult compactNow(Slot slot) {
        if (!(slot.state instanceof LedgerState.Ready ready)) {
            return CompactResult.skipped("the ledger is unrecoverable");
        }
        Ledger kept = Compaction.plan(ready.ledger(), clock.getAsLong(), options.retentionMillis());
        if (kept == ready.ledger()) {
            return CompactResult.nothingToDo();
        }
        try {
            byte[] content = LedgerCodec.encode(kept);
            LedgerFiles.replace(slot.file, content, options.fsync(), hooks.afterForce,
                    () -> hooks.beforeCompactionMove.accept(slot.file));
            slot.fileLength = content.length;
            slot.validLength = content.length;
            slot.state = new LedgerState.Ready(kept);
            return CompactResult.compacted(ready.ledger().records().size() - kept.records().size());
        } catch (IOException | RuntimeException failed) {
            slot.loaded = false; // the rename may or may not have happened: read what is there next time
            log.accept("escrow ledger compaction of " + slot.file + " failed, the previous file is kept: " + failed);
            return CompactResult.failed(String.valueOf(failed));
        }
    }

    /** One diagnostic per player per code per minute (§7), so a failing disk cannot flood the log. */
    private void report(UUID player, dev.materione.api.diagnostics.DiagnosticCode code, String message, String hint) {
        long now = clock.getAsLong();
        String key = player + "/" + code.code();
        Long last = reported.get(key);
        if (last != null && now - last < REPORT_EVERY_MILLIS && now >= last) {
            return;
        }
        reported.put(key, now);
        diagnostics.accept(new Diagnostic(code, Severity.WARN, message, null, null, hint));
    }

    // ---- per-player state ---------------------------------------------------------------------------------------

    private static final class Slot {

        final UUID player;
        final Path file;
        final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        /** A drain task is queued or running: at most one is outstanding per player. */
        final AtomicBoolean scheduled = new AtomicBoolean();
        /** Held while tasks run: one drainer at a time, whether a pool task or {@link #close}. */
        final ReentrantLock drainLock = new ReentrantLock();
        /** Written by the IO task, read by anyone ({@link #preloaded}). Null until the first load settles. */
        volatile LedgerState state;
        volatile long lastUse;
        /** Guarded by {@code this}: no offer is accepted once set. */
        boolean retired;
        // touched only under {@link #drainLock}
        boolean loaded;
        int validLength;
        long fileLength;

        Slot(UUID player, Path file) {
            this.player = player;
            this.file = file;
        }

        synchronized boolean offer(Runnable task, long now) {
            if (retired) {
                return false;
            }
            lastUse = now;
            queue.add(task);
            return true;
        }
    }

    /** Test seams; {@link #NONE} in production. */
    static final class Hooks {

        static final Hooks NONE = new Hooks();

        Consumer<UUID> beforeAppend = player -> {};
        Consumer<Path> beforeCompactionMove = file -> {};
        Runnable afterWrite = () -> {};
        Runnable afterForce = () -> {};
    }
}
