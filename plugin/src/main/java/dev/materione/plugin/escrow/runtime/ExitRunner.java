package dev.materione.plugin.escrow.runtime;

import dev.materione.plugin.escrow.gate.CrashGate;
import dev.materione.plugin.escrow.io.AppendResult;
import dev.materione.plugin.escrow.io.EscrowLedgerStore;
import dev.materione.plugin.escrow.io.LedgerState;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.Refusal;
import java.util.UUID;

/**
 * Tier 2, steps 2 to 4, for an exit whose step 1 just ran (gui-input-durability.md §5.4, §12 row B; #247): the
 * clean entry point #248's consume action (and any later hand-off) calls. Steps 1 and the validation before it are the
 * caller's ({@link Tier1#beginExit} for an incoming stack, {@link Tier1#freezeSlot} for one already in a slot); this
 * class carries the exit through the rest.
 *
 * <pre>
 * 1  owner  Tier1.beginExit / freezeSlot   source removed, E frozen EXITING(T) in the account        (the caller)
 * 2  IO     ledger.append(INTENT)          the decision point is the CRC-valid record                (here)
 * 3  owner  Tier1.completeExit             on the ack: E deleted, the slot cleared; refused instead   (here)
 *                                          -> Tier1.abortExit when the append is refused
 * 4  both   SaveBarrier.await              saveData, read back, ExitCallbacks.acknowledged, DONE     (here)
 * </pre>
 *
 * <p>An exit with no effect (#248's trash) implements no callback beyond what it wants to tell the player: the deletion
 * is visible at step 3 and nothing waits; its DONE follows when the server's own next save is seen, with no save
 * requested (D8). An exit with an effect (a flow that pays money after consuming) returns true from
 * {@link ExitCallbacks#carriesEffect()} and runs the effect in {@link ExitCallbacks#acknowledged()}, and nowhere else.
 *
 * <p>The continuation after the append hops back to the player with {@link Owner#run}, never a blocking wait (§5.6).
 * If the player is gone by then the decision is durable and recovery finishes it (K14): the exit is reported as
 * {@link ExitCallbacks.Abandoned#PLAYER_LEFT} and the quit save is polled for its DONE.
 *
 * @param <T> one stack
 */
public final class ExitRunner<T> {

    private final Tier1<T> tier1;
    private final EscrowLedgerStore ledger;
    private final SaveBarrier barrier;

    public ExitRunner(Tier1<T> tier1, EscrowLedgerStore ledger, SaveBarrier barrier) {
        this.tier1 = tier1;
        this.ledger = ledger;
        this.barrier = barrier;
    }

    /**
     * Steps 2 to 4 for {@code begun}, which {@link Tier1#beginExit} or {@link Tier1#freezeSlot} returned on this
     * (the player's own) thread. Returns at once; the callbacks fire as the steps complete.
     */
    public void proceed(Owner<T> owner, Tier1.Begun begun, ExitCallbacks callbacks) {
        UUID player = owner.id();
        CrashGate.at(CrashGate.Gates.EXIT_BEGUN); // test-only, inert in production
        ledger.append(player, begun.intent()).whenComplete((result, failure) -> {
            AppendResult outcome = failure != null
                    ? new AppendResult.Refused(AppendResult.Reason.WRITE_FAILED, String.valueOf(failure))
                    : result;
            owner.run(() -> afterAppend(owner, begun, outcome, callbacks), () -> gone(player, begun, outcome, callbacks));
        });
    }

    /** Step 3 (the append is durable) or the abort (it was refused), on the player's own thread. */
    private void afterAppend(Owner<T> owner, Tier1.Begun begun, AppendResult outcome, ExitCallbacks callbacks) {
        PlayerEscrow escrow = owner.escrow();
        Ledger decided = null;
        if (outcome instanceof AppendResult.Durable durable) {
            decided = durable.ledger();
        } else {
            Tier1.Finish aborted = tier1.abortExit(escrow, owner.live(), imageOf(owner.id()), begun.txn());
            if (aborted instanceof Tier1.Declined declined && declined.cause() == Refusal.DECIDED) {
                decided = imageOf(owner.id()); // the record was in the file after all: the decision stands
            } else {
                callbacks.refused(reasonOf(outcome));
                return;
            }
        }
        Tier1.Finish completed = tier1.completeExit(escrow, owner.live(), decided, begun.txn());
        CrashGate.at(CrashGate.Gates.EXIT_APPLIED); // test-only, inert in production
        callbacks.decided(completed instanceof Tier1.Finished finished && finished.applied());
        barrier.await(owner, begun.intent(), begun.exitGen(), callbacks);
    }

    /** The player was removed before the continuation could run. */
    private void gone(UUID player, Tier1.Begun begun, AppendResult outcome, ExitCallbacks callbacks) {
        callbacks.abandoned(ExitCallbacks.Abandoned.PLAYER_LEFT);
        if (outcome instanceof AppendResult.Durable) {
            barrier.orphan(player, begun.intent(), begun.exitGen());
        }
        // a refused append decided nothing: the frozen entry is in the saved account without an INTENT, which recovery returns
    }

    private Ledger imageOf(UUID player) {
        return ledger.preloaded(player)
                .filter(LedgerState.Ready.class::isInstance)
                .map(state -> ((LedgerState.Ready) state).ledger())
                .orElse(Ledger.EMPTY);
    }

    private static Tier1.Reason reasonOf(AppendResult outcome) {
        if (outcome instanceof AppendResult.Refused refused && refused.reason() == AppendResult.Reason.WRITE_FAILED) {
            return Tier1.Reason.UNRECORDABLE;
        }
        return Tier1.Reason.UNAVAILABLE;
    }
}
