package dev.materione.plugin.escrow.runtime;

import dev.materione.plugin.escrow.gate.CrashGate;
import dev.materione.plugin.escrow.io.AppendResult;
import dev.materione.plugin.escrow.io.EscrowLedgerStore;
import dev.materione.plugin.escrow.io.ReadBack;
import dev.materione.plugin.escrow.io.SnapshotReader;
import dev.materione.plugin.escrow.model.BarrierBook;
import dev.materione.plugin.escrow.model.Exits;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.LedgerRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Tier 3, the barrier, which is the acknowledgment (gui-input-durability.md §5.4, §5.6, §12 row B; #247): proof that a
 * snapshot containing a decided exit is on disk, and what that proof releases.
 *
 * <pre>
 * owner:  await(exit)          the exit is decided (INTENT durable); it waits
 * owner:  Owner#save()         only if an effect waits, once per player per cooldown: the platform's own Player.saveData()
 * IO:     read the file back   the account's gen in &lt;uuid&gt;.dat, retried while the server replaces the file (F2)
 * owner:  gen &gt;= exitGen        ExitCallbacks#acknowledged  (an effect that waited runs, once)
 * IO:     append DONE          the exit is acknowledged in the ledger (compaction may drop the pair, §5.3)
 * </pre>
 *
 * <h2>The D8 exception, and its limits</h2>
 *
 * {@link Owner#save()} is the one synchronous IO design admits on an owner thread (D8 amendment, §13.9), and this
 * class holds its whole justification: it is the <em>only</em> caller (a build rule fails the build on any other call
 * of {@code Player.saveData}, and on any other class that calls {@link Owner#save}); it calls it only when the
 * {@link BarrierBook} allows it (once per player per cooldown, doubling after fruitless cycles); it is reached only
 * from {@link #await}, that is only for an exit whose decision is already durable <em>and that carries an effect</em>
 * ({@link ExitCallbacks#carriesEffect}), and never from a tier-1 step or from recovery. An exit with no effect (#248's
 * trash) requests no save: it is acknowledged by the next snapshot the server saves by itself, found by a read-back
 * every {@link BarrierBook#POLL_INTERVAL_MILLIS}, so the trash costs a file read and no synchronous IO. Everything else it does, the read-back, the DONE append, runs on the IO executor; its continuations hop
 * back to the owner with {@link Owner#run}, never a blocking wait.
 *
 * <h2>Why "not acknowledged" is safe (K23)</h2>
 *
 * The only thing that releases an effect is {@link BarrierBook#readBack} with a {@code gen} that the read-back read
 * from the file. A kill between {@code saveData()} and the read-back, a read-back that finds an older {@code gen}, an
 * unreadable file, a thrown {@code saveData()}, a quit, a give-up and a shutdown all end in no
 * {@link ExitCallbacks#acknowledged}: the effect never ran, and recovery applies the INTENT (K11 and K12). An effect is
 * delivered at most once, after the decision and the snapshot are both durable.
 *
 * <p>The effect is released at the proof, before the DONE append: DONE only lets compaction drop the pair and ends no
 * exposure the proof had not already ended, so a failed DONE costs 70 bytes of ledger, never an effect.
 *
 * <p>Threading: a player's exits are registered and their results handled on that player's own thread; the per-player
 * state is guarded by its monitor anyway, because {@link #left} and {@link #close} come from the quit event and the
 * disabling thread. User callbacks are never called while the monitor is held.
 */
public final class SaveBarrier {

    private final EscrowLedgerStore ledger;
    private final SnapshotReader snapshots;
    private final LongSupplier clock;
    private final long cooldownMillis;
    private final long giveUpMillis;
    private final Consumer<String> log;
    private final ConcurrentMap<UUID, Slot> slots = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * @param ledger         where DONE is appended
     * @param snapshots      the read-back of the player's file
     * @param cooldownMillis the minimum time between two saves of one player ({@code escrow.barrier.cooldown})
     * @param giveUpMillis   how long an exit waits before its effect is given up
     * @param log            receives a line for a read-back that did not confirm, once per player, and for a failure
     */
    public SaveBarrier(EscrowLedgerStore ledger, SnapshotReader snapshots, LongSupplier clock, long cooldownMillis,
            long giveUpMillis, Consumer<String> log) {
        this.ledger = ledger;
        this.snapshots = snapshots;
        this.clock = clock;
        this.cooldownMillis = cooldownMillis;
        this.giveUpMillis = giveUpMillis;
        this.log = log;
        new BarrierBook(cooldownMillis, giveUpMillis); // validates the numbers now, not at the first exit
    }

    /**
     * A decided exit waits for its acknowledgment. Call on the player's own thread, after the INTENT's append completed
     * ({@code Exits.complete}'s precondition): the barrier is for decided exits only.
     *
     * @param owner    the player
     * @param intent   the exit's durable INTENT
     * @param exitGen  the {@code gen} of the account after step 1
     * @param callbacks told {@link ExitCallbacks#acknowledged} or {@link ExitCallbacks#abandoned}, once
     */
    public void await(Owner<?> owner, LedgerRecord.Intent intent, long exitGen, ExitCallbacks callbacks) {
        if (closed) {
            callbacks.abandoned(ExitCallbacks.Abandoned.SHUTDOWN);
            return;
        }
        Slot slot = slots.computeIfAbsent(owner.id(), id -> new Slot(owner, new BarrierBook(cooldownMillis, giveUpMillis)));
        synchronized (slot) {
            slot.owner = owner;
            slot.book.register(intent, exitGen, callbacks.carriesEffect(), clock.getAsLong());
            slot.callbacks.put(intent.txn(), callbacks);
        }
        pump(slot);
    }

    /**
     * A decided exit whose player is already gone (they left before step 3 could run): there is nothing to wait for on
     * a thread, but the quit save may still land, so the file is polled and DONE is appended when it does.
     */
    public void orphan(UUID player, LedgerRecord.Intent intent, long exitGen) {
        if (closed) {
            return;
        }
        snapshots.awaitAtLeast(player, exitGen).thenAccept(reached -> {
            if (reached.isPresent()) {
                Exits.acknowledge(new Ledger(List.of(intent)), intent.txn(), exitGen, reached.getAsLong())
                        .ifPresent(done -> appendDone(player, done));
            }
        });
    }

    /**
     * The player left: nothing waiting for them can be released any more. Their effects are abandoned and the quit
     * save, which follows this event, is polled for the DONE records. Call from the quit handler.
     */
    public void left(UUID player) {
        Slot slot = slots.remove(player);
        if (slot == null) {
            return;
        }
        Map<Id128, ExitCallbacks> callbacks;
        List<BarrierBook.Pending> gone;
        synchronized (slot) {
            gone = slot.book.drain();
            callbacks = new HashMap<>(slot.callbacks);
            slot.callbacks.clear();
        }
        for (BarrierBook.Pending exit : gone) {
            callAbandoned(callbacks.get(exit.txn()), ExitCallbacks.Abandoned.PLAYER_LEFT);
            orphan(player, exit.intent(), exit.exitGen());
        }
    }

    /** Plugin disable: every waiting exit is abandoned; nothing more is accepted. Idempotent. */
    public void close() {
        closed = true;
        for (UUID player : List.copyOf(slots.keySet())) {
            Slot slot = slots.remove(player);
            if (slot == null) {
                continue;
            }
            Map<Id128, ExitCallbacks> callbacks;
            List<BarrierBook.Pending> gone;
            synchronized (slot) {
                gone = slot.book.drain();
                callbacks = new HashMap<>(slot.callbacks);
                slot.callbacks.clear();
            }
            for (BarrierBook.Pending exit : gone) {
                callAbandoned(callbacks.get(exit.txn()), ExitCallbacks.Abandoned.SHUTDOWN);
            }
        }
    }

    /** How many exits wait for this player (tests, {@code doctor}). */
    public int waiting(UUID player) {
        Slot slot = slots.get(player);
        if (slot == null) {
            return 0;
        }
        synchronized (slot) {
            return slot.book.waiting().size();
        }
    }

    // ---- the cycle: decide, save, read back, release ----------------------------------------------------------

    /** Decides what to do next for the player, on their own thread: nothing, wait for the cooldown, or save now. */
    private void pump(Slot slot) {
        List<Runnable> afterUnlock = new ArrayList<>();
        boolean save = false;
        boolean poll = false;
        long wait = -1;
        synchronized (slot) {
            if (closed || slots.get(slot.owner.id()) != slot) {
                return;
            }
            long now = clock.getAsLong();
            for (BarrierBook.Pending exit : slot.book.expire(now)) {
                ExitCallbacks callbacks = slot.callbacks.remove(exit.txn());
                log.accept("MATERIONE-ESCROW the barrier gave up on exit " + exit.txn() + " of " + slot.owner.id()
                        + ": no saved snapshot with it was proven within " + giveUpMillis / 1000 + " s; its effect will"
                        + " not run (the decision stands: recovery applies it)");
                afterUnlock.add(() -> callAbandoned(callbacks, ExitCallbacks.Abandoned.TIMED_OUT));
            }
            switch (slot.book.decide(now)) {
                case BarrierBook.Save s -> save = true;
                case BarrierBook.Poll p -> poll = true;
                case BarrierBook.Wait w -> {
                    if (!slot.timer) {
                        slot.timer = true;
                        wait = w.millis();
                    }
                }
                case BarrierBook.Idle i -> { }
            }
        }
        afterUnlock.forEach(Runnable::run);
        if (wait >= 0) {
            Runnable wake = () -> {
                synchronized (slot) {
                    slot.timer = false;
                }
                pump(slot);
            };
            slot.owner.runAfter(wait, wake, () -> {
                synchronized (slot) {
                    slot.timer = false;
                }
            });
        }
        if (save) {
            saveNow(slot);
        } else if (poll) {
            pollNow(slot);
        }
    }

    /**
     * The only path to {@link Owner#save}: the book has just allowed it ({@link BarrierBook#saved} refuses otherwise),
     * the player's own thread is the caller, and a read-back is started whatever the save does, because only the
     * read-back acknowledges.
     */
    private void saveNow(Slot slot) {
        Owner<?> owner;
        synchronized (slot) {
            owner = slot.owner;
            try {
                slot.book.saved(clock.getAsLong(), owner.escrow().account().gen());
            } catch (IllegalStateException rateLimited) {
                return; // another cycle began between the decision and here: its read-back will pump again
            }
        }
        try {
            owner.save();
        } catch (RuntimeException failed) {
            log.accept("MATERIONE-ESCROW saving " + owner.id() + " for the barrier failed: " + failed
                    + " (not acknowledged; it will be retried)");
        }
        CrashGate.at(CrashGate.Gates.BARRIER_SAVED); // test-only, inert in production (K23: the kill between save and read-back)
        readBack(slot, owner, true);
    }

    /**
     * Every exit waiting has no effect, so no save is requested (D8): the file is read back to find the snapshot the
     * server saved on its own (its autosave, a quit, any other plugin's save).
     */
    private void pollNow(Slot slot) {
        Owner<?> owner;
        synchronized (slot) {
            owner = slot.owner;
            try {
                slot.book.polled(clock.getAsLong());
            } catch (IllegalStateException notDue) {
                return;
            }
        }
        readBack(slot, owner, false);
    }

    private void readBack(Slot slot, Owner<?> owner, boolean afterSave) {
        snapshots.read(owner.id()).whenComplete((found, failure) -> {
            ReadBack result = failure != null ? new ReadBack.Unavailable("read-back failed: " + failure) : found;
            owner.run(() -> afterReadBack(slot, result, afterSave), null);
        });
    }

    /** The read-back finished, back on the player's own thread: release what it proves, then carry on. */
    private void afterReadBack(Slot slot, ReadBack found, boolean afterSave) {
        List<BarrierBook.Acked> acked;
        List<ExitCallbacks> released = new ArrayList<>();
        synchronized (slot) {
            if (slots.get(slot.owner.id()) != slot) {
                return; // the player left meanwhile: left() polls the file instead
            }
            OptionalLong gen = found instanceof ReadBack.Saved saved ? OptionalLong.of(saved.gen()) : OptionalLong.empty();
            acked = slot.book.readBack(gen);
            for (BarrierBook.Acked exit : acked) {
                released.add(slot.callbacks.remove(exit.exit().txn()));
            }
            if (acked.isEmpty() && afterSave && !slot.warned) {
                slot.warned = true;
                log.accept("MATERIONE-ESCROW the read-back of " + slot.owner.id() + " did not confirm a snapshot with"
                        + " the exit yet (" + describe(found) + "); the barrier retries, an effect waiting on it does"
                        + " not run until it does");
            }
        }
        for (int i = 0; i < acked.size(); i++) {
            ExitCallbacks callbacks = released.get(i);
            if (callbacks != null) {
                try {
                    callbacks.acknowledged();
                } catch (RuntimeException failed) {
                    log.accept("MATERIONE-ESCROW an exit's effect failed after its acknowledgment: " + failed);
                }
            }
            appendDone(slot.owner.id(), acked.get(i).done());
        }
        pump(slot);
    }

    private static String describe(ReadBack found) {
        return switch (found) {
            case ReadBack.Saved saved -> "the file holds gen " + Long.toUnsignedString(saved.gen());
            case ReadBack.NoAccount none -> none.detail();
            case ReadBack.Unavailable none -> none.detail();
        };
    }

    private void appendDone(UUID player, LedgerRecord.Done done) {
        ledger.append(player, done).whenComplete((result, failure) -> {
            if (failure != null || result instanceof AppendResult.Refused) {
                log.accept("MATERIONE-ESCROW the DONE of exit " + done.txn() + " was not recorded ("
                        + (failure != null ? failure : ((AppendResult.Refused) result).detail())
                        + "); harmless: the exit stays in the ledger until a later compaction can drop it");
            }
        });
    }

    private void callAbandoned(ExitCallbacks callbacks, ExitCallbacks.Abandoned why) {
        if (callbacks == null) {
            return;
        }
        try {
            callbacks.abandoned(why);
        } catch (RuntimeException failed) {
            log.accept("MATERIONE-ESCROW an exit's abandon handler failed: " + failed);
        }
    }

    private static final class Slot {

        final BarrierBook book;
        final Map<Id128, ExitCallbacks> callbacks = new HashMap<>();
        Owner<?> owner;
        /** A wake-up is scheduled for the cooldown. */
        boolean timer;
        boolean warned;

        Slot(Owner<?> owner, BarrierBook book) {
            this.owner = owner;
            this.book = book;
        }
    }
}
