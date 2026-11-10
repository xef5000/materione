package dev.materione.plugin.escrow.io;

import dev.materione.plugin.escrow.model.AccountRead;
import dev.materione.plugin.escrow.model.Inventory;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.LedgerRead;
import dev.materione.plugin.escrow.model.Recovery;
import java.util.Objects;

/**
 * What the IO half knows of one player's ledger: the in-memory image the join path reads without any IO
 * (gui-input-durability.md §5.7), or the reason the player's strict transfers are refused (§5.3, K18).
 */
public sealed interface LedgerState {

    /**
     * The ledger is readable. The image holds exactly the records that are durable in the file (an append joins it
     * only after its write, and its force when {@code fsync} is on, returned).
     *
     * @param ledger the durable records, in file order
     */
    record Ready(Ledger ledger) implements LedgerState {
        public Ready {
            Objects.requireNonNull(ledger, "ledger");
        }
    }

    /**
     * The ledger cannot be trusted: nothing is returned or deleted for the player and strict transfers are refused
     * until an admin resolves it by hand ({@code MTR-0101}). The file is preserved untouched, or renamed aside.
     *
     * @param why    the cause
     * @param file   the file concerned (for a corrupt ledger, where it was moved to), for the diagnostic
     * @param detail a short, stable description
     */
    record Unrecoverable(Why why, String file, String detail) implements LedgerState {
        public Unrecoverable {
            Objects.requireNonNull(why, "why");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** Why a ledger is unrecoverable. */
    enum Why {
        /** A bad record before the end of the file: it was just renamed to {@code .corrupt-<millis>} (K18). */
        CORRUPT,
        /** A {@code .corrupt-<millis>} file of an earlier quarantine still lies beside the ledger: unresolved. */
        QUARANTINED,
        /** The file is a newer version than this reader: opaque and read-only, never appended to. */
        NEWER_VERSION,
        /** The file could not be read (permissions, a failing disk). Retried the next time the player is loaded. */
        IO_ERROR
    }

    /**
     * The recovery input of the join handler (§5.7), with no IO: recovery of the saved account against this state.
     * A {@link Ready} state recovers normally; an {@link Unrecoverable} one gives
     * {@link Recovery.Result.Unrecoverable} (an unreadable account is reported first, as {@link Recovery} does).
     */
    default Recovery.Result recover(AccountRead saved, Inventory inventory) {
        return switch (this) {
            case Ready ready -> Recovery.recover(saved, new LedgerRead.Valid(ready.ledger(), 0, 0), inventory);
            case Unrecoverable unrecoverable -> saved instanceof AccountRead.Unreadable unreadable
                    ? new Recovery.Result.Unrecoverable(Recovery.Cause.ACCOUNT, unreadable.reason().name())
                    : new Recovery.Result.Unrecoverable(
                            Recovery.Cause.LEDGER, unrecoverable.why() + ": " + unrecoverable.detail());
        };
    }
}
