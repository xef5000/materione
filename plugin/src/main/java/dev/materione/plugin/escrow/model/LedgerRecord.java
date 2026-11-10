package dev.materione.plugin.escrow.model;

import java.util.Objects;

/**
 * One record of the per-player exit ledger (gui-input-durability.md §5.3, §5.4). Records only ever grow the log.
 */
public sealed interface LedgerRecord {

    /** Largest value of a {@code u32} field. */
    long U32_MAX = 0xFFFFFFFFL;

    /** The transaction this record belongs to. */
    Id128 txn();

    /**
     * The exit decision: from the moment a CRC-valid INTENT is in the file the entry is never returned (G2, I5).
     *
     * @param txn       the transaction
     * @param entryId   the entry the decision is about (recovery matches by this id)
     * @param kind      what the exit does
     * @param stackHash {@link StackBytes#hash16()} of the stack, for diagnostics and audit
     * @param amount    how many items the exit takes (a {@code u32})
     * @param atMillis  when the decision was made (epoch millis)
     */
    record Intent(Id128 txn, Id128 entryId, ExitKind kind, Id128 stackHash, long amount, long atMillis)
            implements LedgerRecord {

        /** @throws IllegalArgumentException on a zero id or an amount a {@code u32} cannot hold */
        public Intent {
            Objects.requireNonNull(txn, "txn");
            Objects.requireNonNull(entryId, "entryId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(stackHash, "stackHash");
            if (txn.isZero() || entryId.isZero()) {
                throw new IllegalArgumentException("an INTENT needs a transaction and an entry id");
            }
            if (amount < 0 || amount > U32_MAX) {
                throw new IllegalArgumentException("amount out of range: " + amount);
            }
        }
    }

    /**
     * The exit is acknowledged: a snapshot that no longer needs the decision's protection is durable (tier 3, row B).
     * Inert for recovery.
     *
     * @param txn the transaction
     */
    record Done(Id128 txn) implements LedgerRecord {

        /** @throws IllegalArgumentException on a zero transaction */
        public Done {
            Objects.requireNonNull(txn, "txn");
            if (txn.isZero()) {
                throw new IllegalArgumentException("a DONE needs a transaction");
            }
        }
    }
}
