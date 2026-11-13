package dev.materione.plugin.escrow.runtime;

import dev.materione.plugin.escrow.model.ExitKind;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The custody consume primitive (#248; gui-input-durability.md §5.4, §12 row X): consume the stack that sits in
 * custody at one window slot, atomically, through the tier-2 exit. It is the one place the exit machinery of rows B and
 * H meets a caller: the {@code custody.consume} action and {@code gui.slot_item clear} on a {@code durable: required}
 * layer both come through {@link #consume}.
 *
 * <pre>
 * 1  owner  Tier1.freezeSlot          the held entry freezes EXITING(T); refused here ({@link Started.Declined}) or goes on
 * 2  IO     ledger.append(INTENT)      the decision point; refused -> the stack goes back, {@link Outcome.Refused}
 * 3  owner  Tier1.completeExit         the entry is deleted, the slot cleared      -> {@link Reach#DECIDED}
 * 4  both   SaveBarrier                a read-back proves a snapshot with the exit -> {@link Reach#SAVED}  (only if asked)
 * </pre>
 *
 * <p><b>What the caller waits for.</b> {@link Reach#DECIDED}: the stage completes at step 3 (the consume is durable and
 * visible; no save is requested and none is waited for, D8). {@link Reach#SAVED}: the stage completes only when the
 * barrier has read a saved snapshot that contains the exit back from disk, and the exit is registered as one that
 * <em>carries an effect</em>, which is the only kind that makes the barrier ask the platform for a save
 * ({@link ExitCallbacks#carriesEffect()}). What the flow does after the stage completes is the effect, so it can never
 * run before the barrier proved the save (§5.4, K23). If the proof never comes (the player leaves, the plugin stops, no
 * snapshot within the give-up time) the stage completes {@link Outcome.Abandoned} and the effect does not run; the
 * decision stands and recovery applies it.
 *
 * <p>Pure: generic over the stack, no Bukkit, no IO, no blocking wait; every step runs where {@link ExitRunner} runs it.
 *
 * @param <T> one stack
 */
public final class CustodyConsume<T> {

    /** How far the exit has to get before the caller continues. */
    public enum Reach {
        /** The consume is decided (the INTENT is durable) and applied: the stack is gone. No save is waited for. */
        DECIDED,
        /** A saved snapshot that contains the consume was read back from disk (tier 3): safe to release an effect. */
        SAVED
    }

    /** What step 1 did. */
    public sealed interface Started<T> {}

    /** Step 1 changed nothing: the slot holds no custody entry, is busy, the gate is shut or the account cannot record it. */
    public record Declined<T>(Tier1.Reason reason, String detail) implements Started<T> {}

    /**
     * Step 1 happened (the entry is frozen); the stage completes as the exit moves on.
     *
     * @param outcome completes on the player's thread, except {@link Outcome.Abandoned} with a shutdown or a player who
     *     left, which may complete on another thread
     */
    public record Running<T>(CompletionStage<Outcome> outcome) implements Started<T> {}

    /** How a running consume ended for its caller. */
    public sealed interface Outcome {}

    /** The consume reached what was asked: {@code reached} is the stage it completed at. */
    public record Consumed(Reach reached) implements Outcome {}

    /** The INTENT could not be recorded: nothing was decided, the stack went back to the player (§7, MTR-0102). */
    public record Refused(Tier1.Reason reason) implements Outcome {}

    /**
     * The exit is decided but the proof asked for will not come in this session. Only for {@link Reach#SAVED}: a plain
     * consume has already completed at {@link Reach#DECIDED}.
     */
    public record Abandoned(ExitCallbacks.Abandoned why) implements Outcome {}

    private final Tier1<T> tier1;
    private final ExitRunner<T> exits;

    public CustodyConsume(Tier1<T> tier1, ExitRunner<T> exits) {
        this.tier1 = tier1;
        this.exits = exits;
    }

    /**
     * Consumes the stack held in escrowed window slot {@code slot} of {@code gui}. Call on the player's own thread.
     *
     * @param owner the player
     * @param gui   the GUI the window shows (the entry's origin)
     * @param slot  the window slot
     * @param reach what the returned stage waits for
     */
    public Started<T> consume(Owner<T> owner, String gui, int slot, Reach reach) {
        Tier1.Begin begin = tier1.freezeSlot(owner.escrow(), owner.live(), gui, slot, ExitKind.CONSUME);
        if (begin instanceof Tier1.Declined declined) {
            return new Declined<>(declined.reason(), declined.cause() == null ? null : declined.cause().name());
        }
        CompletableFuture<Outcome> outcome = new CompletableFuture<>();
        exits.proceed(owner, (Tier1.Begun) begin, new Callbacks(reach, outcome));
        return new Running<>(outcome);
    }

    /** The exit's callbacks, folded into one outcome: the first thing that settles the caller's question wins. */
    private record Callbacks(Reach reach, CompletableFuture<Outcome> outcome) implements ExitCallbacks {

        @Override
        public boolean carriesEffect() {
            return reach == Reach.SAVED;
        }

        @Override
        public void refused(Tier1.Reason reason) {
            outcome.complete(new Refused(reason));
        }

        @Override
        public void decided(boolean applied) {
            if (reach == Reach.DECIDED) {
                outcome.complete(new Consumed(Reach.DECIDED));
            }
        }

        @Override
        public void acknowledged() {
            outcome.complete(new Consumed(Reach.SAVED));
        }

        @Override
        public void abandoned(Abandoned why) {
            outcome.complete(new CustodyConsume.Abandoned(why));
        }
    }
}
