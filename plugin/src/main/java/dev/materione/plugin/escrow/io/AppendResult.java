package dev.materione.plugin.escrow.io;

import dev.materione.plugin.escrow.model.Ledger;
import java.util.Objects;

/**
 * The acknowledgment of one append (gui-input-durability.md §5.4 step 2): completed on an IO thread, after the
 * write (and the force, when {@code fsync} is on) returned. A continuation must hop back to the owner thread
 * through the scheduling gateway; it never blocks on the future (§5.6).
 */
public sealed interface AppendResult {

    /**
     * The record is durable: a CRC-valid record is in the file. This is the decision point of an exit.
     *
     * @param ledger the player's ledger image including the record: the input of {@code Exits.complete} (step 3)
     */
    record Durable(Ledger ledger) implements AppendResult {
        public Durable {
            Objects.requireNonNull(ledger, "ledger");
        }
    }

    /**
     * The record is not durable and never will be through this call: the caller aborts the exit with
     * {@code Exits.abortUndecided} (nothing was decided) and refuses the transfer (I7).
     *
     * @param reason why
     * @param detail the cause, for the message and the diagnostic ({@code MTR-0102} was already reported for
     *               {@link Reason#WRITE_FAILED})
     */
    record Refused(Reason reason, String detail) implements AppendResult {
        public Refused {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** Why an append was refused. */
    enum Reason {
        /** The player's ledger is unrecoverable ({@link LedgerState.Unrecoverable}): nothing is ever appended. */
        UNRECOVERABLE,
        /** The write or the force failed (disk full, permissions): the record may be absent or a torn tail. */
        WRITE_FAILED,
        /** The store is closed (the plugin is disabling). */
        SHUTDOWN
    }
}
