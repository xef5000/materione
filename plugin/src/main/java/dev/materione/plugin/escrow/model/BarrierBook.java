package dev.materione.plugin.escrow.model;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Tier 3, the barrier's bookkeeping for one player (gui-input-durability.md §5.4, §12 row B): which decided exits wait
 * for an acknowledged snapshot, when the platform's own save may be requested again, and which of them a read-back
 * of the saved file proves. Pure and deterministic: the clock is a {@code long} the caller passes, the save and the
 * read-back are the caller's. One book per online player, used on that player's own thread.
 *
 * <p><b>The rule that makes "not acknowledged" safe (K23).</b> An exit is acknowledged only by {@link #readBack}, and
 * only with a saved {@code gen} the read-back actually read ({@link Exits#acknowledge}: the INTENT is durable and the
 * snapshot's {@code gen} has reached the exit's step-1 {@code gen}, unsigned). A save that was requested but not
 * confirmed, a read-back that found an older {@code gen}, no account, an unreadable file or nothing at all, all leave
 * the exit pending: nothing in this class turns "asked" or "probably" into "acknowledged".
 *
 * <p><b>Who gets a save.</b> The one synchronous IO design asks for is the platform's own {@code Player.saveData()}, and
 * D8's exception admits it only for an exit that carries an effect (an effect must wait for proof, so the proof is
 * requested). An exit with no effect (#248's trash) asks for nothing: it is acknowledged by whichever snapshot the server
 * saves anyway (the autosave), found by {@link #polled read-backs} every {@link #POLL_INTERVAL_MILLIS}, and a save that
 * an effect-carrying exit caused acknowledges it too.
 *
 * <p><b>Rate limit.</b> A save is allowed once per {@code cooldown} per player ({@link #decide}); every fruitless cycle
 * (a save whose read-back acknowledged nothing) doubles the wait, up to {@link #MAX_COOLDOWN_MILLIS}, so a disk that
 * cannot save is not hammered. {@link #saved} refuses a save the cooldown does not allow, so the limit cannot be
 * bypassed by a caller that forgot to ask.
 *
 * <p><b>Coverage.</b> A save taken when the live account was at {@code gen} g contains every exit whose step-1 gen is
 * at most g, so only the read-back that follows it can acknowledge those; an exit registered with a higher gen waits
 * for the next save. Exits older than {@code giveUp} are expired: their effect never runs.
 */
public final class BarrierBook {

    /** {@code escrow.barrier.cooldown}: one save per player per 5 s by default (§5.4). */
    public static final long DEFAULT_COOLDOWN_MILLIS = 5_000L;

    /** The longest the cooldown grows to after repeated fruitless cycles. */
    public static final long MAX_COOLDOWN_MILLIS = 300_000L;

    /** How long an exit waits for its acknowledgment before its effect is given up (never run). */
    public static final long DEFAULT_GIVE_UP_MILLIS = 600_000L;

    /** How often an exit with no effect looks for the snapshot the server saves by itself (no save is requested). */
    public static final long POLL_INTERVAL_MILLIS = 30_000L;

    private static final long NEVER = Long.MIN_VALUE;
    private static final int MAX_DOUBLINGS = 10;

    /**
     * A decided exit waiting for its acknowledgment.
     *
     * @param intent           the exit's durable decision (the INTENT whose append completed)
     * @param exitGen          the {@code gen} of the account after step 1 (the exit is in any snapshot at or above it)
     * @param registeredMillis when it started waiting
     * @param carriesEffect    whether an effect waits for the proof: only such an exit makes the barrier request a save
     */
    public record Pending(LedgerRecord.Intent intent, long exitGen, long registeredMillis, boolean carriesEffect) {

        /** The transaction. */
        public Id128 txn() {
            return intent.txn();
        }
    }

    /** An exit the read-back proved, with the DONE record to append. */
    public record Acked(Pending exit, LedgerRecord.Done done) {}

    /** What to do next for this player. */
    public sealed interface Action {}

    /** Nothing to do: nothing waits, or a read-back is in flight and will ask again. */
    public record Idle() implements Action {}

    /** Ask again after this many milliseconds (the cooldown has not elapsed). */
    public record Wait(long millis) implements Action {}

    /** Request the platform's save now, then {@link #saved}. */
    public record Save() implements Action {}

    /** Read the file back now without requesting a save (only exits with no effect wait), then {@link #polled}. */
    public record Poll() implements Action {}

    private final long baseCooldownMillis;
    private final long giveUpMillis;
    private final Map<Id128, Pending> pending = new LinkedHashMap<>();
    private long lastSaveMillis = NEVER;
    private long lastPollMillis = NEVER;
    private boolean saveCycle;
    private long coveredGen;
    private boolean covering;
    private boolean inFlight;
    private int fruitless;

    /**
     * @param cooldownMillis the minimum time between two saves of this player; at least 1, at most
     *     {@link #MAX_COOLDOWN_MILLIS}
     * @param giveUpMillis   how long an exit waits for its acknowledgment; at least 1
     */
    public BarrierBook(long cooldownMillis, long giveUpMillis) {
        if (cooldownMillis < 1 || cooldownMillis > MAX_COOLDOWN_MILLIS || giveUpMillis < 1) {
            throw new IllegalArgumentException("cooldown must be in [1, " + MAX_COOLDOWN_MILLIS + "] ms and give-up positive");
        }
        this.baseCooldownMillis = cooldownMillis;
        this.giveUpMillis = giveUpMillis;
    }

    /** A book with the shipped defaults. */
    public static BarrierBook withDefaults() {
        return new BarrierBook(DEFAULT_COOLDOWN_MILLIS, DEFAULT_GIVE_UP_MILLIS);
    }

    /**
     * A decided exit starts waiting. {@code intent} is the INTENT whose append completed: the book is only fed
     * decisions that are durable (§5.4 step 3 precedes it).
     *
     * @param carriesEffect whether an effect runs on the proof (the barrier then requests a save for it)
     * @throws IllegalArgumentException on an exit already waiting
     */
    public void register(LedgerRecord.Intent intent, long exitGen, boolean carriesEffect, long nowMillis) {
        if (pending.putIfAbsent(intent.txn(), new Pending(intent, exitGen, nowMillis, carriesEffect)) != null) {
            throw new IllegalArgumentException("exit " + intent.txn() + " is already waiting");
        }
    }

    /** What to do now (see {@link Action}). */
    public Action decide(long nowMillis) {
        if (pending.isEmpty() || inFlight) {
            return new Idle();
        }
        if (pending.values().stream().anyMatch(Pending::carriesEffect)) {
            long wait = waitMillis(nowMillis);
            return wait <= 0 ? new Save() : new Wait(wait);
        }
        long wait = pollWaitMillis(nowMillis);
        return wait <= 0 ? new Poll() : new Wait(wait);
    }

    private long pollWaitMillis(long nowMillis) {
        long anchor = lastPollMillis != NEVER ? lastPollMillis : pending.values().iterator().next().registeredMillis();
        long elapsed = nowMillis - anchor;
        return elapsed < 0 ? POLL_INTERVAL_MILLIS : POLL_INTERVAL_MILLIS - elapsed;
    }

    private long waitMillis(long nowMillis) {
        if (lastSaveMillis == NEVER) {
            return 0;
        }
        long elapsed = nowMillis - lastSaveMillis;
        return elapsed < 0 ? currentCooldown() : currentCooldown() - elapsed; // a clock that went back waits one full period
    }

    /** The cooldown now in force: the base, doubled for every fruitless cycle in a row, up to the ceiling. */
    public long currentCooldown() {
        return Math.min(MAX_COOLDOWN_MILLIS, baseCooldownMillis << Math.min(fruitless, MAX_DOUBLINGS));
    }

    /**
     * The platform's save is being requested now, with the live account at {@code liveGen}: it covers the waiting
     * exits whose gen it has reached. A read-back must follow and be passed to {@link #readBack}.
     *
     * @throws IllegalStateException when the cooldown has not elapsed, a read-back is still in flight, or no waiting exit
     *     carries an effect: the save rate limit, and D8's "only for an exit that carries an effect", are properties of
     *     this class, not of its callers
     */
    public void saved(long nowMillis, long liveGen) {
        if (pending.values().stream().noneMatch(Pending::carriesEffect)) {
            throw new IllegalStateException("the barrier saves only for an exit that carries an effect");
        }
        if (inFlight || waitMillis(nowMillis) > 0) {
            throw new IllegalStateException("the barrier's save is rate limited: " + waitMillis(nowMillis)
                    + " ms to wait" + (inFlight ? ", a read-back is in flight" : ""));
        }
        lastSaveMillis = nowMillis;
        lastPollMillis = nowMillis;
        coveredGen = liveGen;
        covering = true;
        saveCycle = true;
        inFlight = true;
    }

    /**
     * A read-back is starting without a save (every exit waiting has no effect): the server's own saves are what it can
     * find. Report its result to {@link #readBack}.
     *
     * @throws IllegalStateException when a read-back is in flight or the poll interval has not elapsed
     */
    public void polled(long nowMillis) {
        if (inFlight || (!pending.isEmpty() && pollWaitMillis(nowMillis) > 0)) {
            throw new IllegalStateException("the barrier's poll is not due");
        }
        lastPollMillis = nowMillis;
        saveCycle = false;
        inFlight = true;
    }

    /** Whether a save was requested and its read-back has not been reported yet. */
    public boolean inFlight() {
        return inFlight;
    }

    /**
     * The read-back of the file after a save finished. {@code savedGen} is the {@code gen} of the account it found in
     * the file, empty when it found none (no file, an unreadable file, no account, an unreadable account). Returns the
     * exits it proves, each with its DONE record, and removes them; everything else stays waiting.
     */
    public List<Acked> readBack(OptionalLong savedGen) {
        inFlight = false;
        List<Acked> acked = new ArrayList<>();
        if (savedGen.isPresent()) {
            for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
                Pending exit = it.next();
                var done = Exits.acknowledge(new Ledger(List.of(exit.intent())), exit.txn(), exit.exitGen(),
                        savedGen.getAsLong());
                if (done.isPresent()) {
                    acked.add(new Acked(exit, done.get()));
                    it.remove();
                }
            }
        }
        if (saveCycle) {
            fruitless = acked.isEmpty() ? fruitless + 1 : 0; // only a save that was asked for and found nothing backs off
        }
        return acked;
    }

    /** Removes and returns the exits that waited longer than the give-up time: their effect must never run. */
    public List<Pending> expire(long nowMillis) {
        List<Pending> expired = new ArrayList<>();
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
            Pending exit = it.next();
            if (nowMillis - exit.registeredMillis() >= giveUpMillis) {
                expired.add(exit);
                it.remove();
            }
        }
        return expired;
    }

    /** Removes and returns every waiting exit (the player left, or the plugin is stopping). */
    public List<Pending> drain() {
        List<Pending> all = List.copyOf(pending.values());
        pending.clear();
        return all;
    }

    /** The exits waiting, in registration order. */
    public List<Pending> waiting() {
        return List.copyOf(pending.values());
    }

    /** Whether {@code txn} is waiting. */
    public boolean isWaiting(Id128 txn) {
        return pending.containsKey(txn);
    }

    /** The gen the last save covered (meaningful only after a {@link #saved}). */
    public OptionalLong coveredGen() {
        return covering ? OptionalLong.of(coveredGen) : OptionalLong.empty();
    }
}
