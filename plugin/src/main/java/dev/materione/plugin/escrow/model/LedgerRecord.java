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

    /**
     * One joint transfer decision. It is the only ownership decision between {@code source} and
     * {@code destination}; no consume records are created for either side.
     */
    record TransferIntent(
            Id128 txn,
            TransferHolder source,
            Id128 sourceEntry,
            long sourceGen,
            TransferHolder destination,
            Id128 destinationReservation,
            long destinationGen,
            int destinationSlot,
            StackBytes stack,
            Id128 stackHash,
            long amount,
            long atMillis)
            implements LedgerRecord {

        public TransferIntent {
            Objects.requireNonNull(txn, "txn");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(sourceEntry, "sourceEntry");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(destinationReservation, "destinationReservation");
            Objects.requireNonNull(stackHash, "stackHash");
            if (txn.isZero() || sourceEntry.isZero() || destinationReservation.isZero()) {
                throw new IllegalArgumentException("a transfer intent needs a transaction and non-zero entry ids");
            }
            if (amount < 0 || amount > U32_MAX) {
                throw new IllegalArgumentException("amount out of range: " + amount);
            }
            if (destinationSlot < 0 || destinationSlot > Entry.MAX_SLOT_HINT) {
                throw new IllegalArgumentException("destination slot out of range: " + destinationSlot);
            }
            if (stack != null && (!stack.hash16().equals(stackHash))) {
                throw new IllegalArgumentException("transfer stack hash does not match its bytes");
            }
            if (source.equals(destination) && sourceEntry.equals(destinationReservation)) {
                throw new IllegalArgumentException("a transfer needs two distinct sides");
            }
        }

        /** Reads v2 transfer decisions, which did not carry the stack payload needed to rebuild an old destination. */
        public TransferIntent(Id128 txn, TransferHolder source, Id128 sourceEntry, long sourceGen,
                TransferHolder destination, Id128 destinationReservation, long destinationGen,
                Id128 stackHash, long amount, long atMillis) {
            this(txn, source, sourceEntry, sourceGen, destination, destinationReservation, destinationGen,
                    0, null, stackHash, amount, atMillis);
        }

        /** New transfers always carry enough opaque stack bytes to roll a pre-reservation snapshot forward. */
        public TransferIntent(Id128 txn, TransferHolder source, Id128 sourceEntry, long sourceGen,
                TransferHolder destination, Id128 destinationReservation, long destinationGen, int destinationSlot,
                StackBytes stack, long amount, long atMillis) {
            this(txn, source, sourceEntry, sourceGen, destination, destinationReservation, destinationGen,
                    destinationSlot, Objects.requireNonNull(stack, "stack"), stack.hash16(), amount, atMillis);
        }

        /** The durable operation identity. Snapshot generations and the timestamp are not operation identity. */
        public boolean sameTransfer(TransferIntent other) {
            return other != null
                    && txn.equals(other.txn)
                    && source.equals(other.source)
                    && sourceEntry.equals(other.sourceEntry)
                    && destination.equals(other.destination)
                    && destinationReservation.equals(other.destinationReservation)
                    && destinationSlot == other.destinationSlot
                    && stackHash.equals(other.stackHash)
                    && amount == other.amount
                    && java.util.Objects.equals(stack, other.stack);
        }
    }

    /** Both committed snapshots were read back; the transfer pair may enter normal compaction retention. */
    record TransferDone(Id128 txn) implements LedgerRecord {
        public TransferDone {
            Objects.requireNonNull(txn, "txn");
            if (txn.isZero()) {
                throw new IllegalArgumentException("a transfer DONE needs a transaction");
            }
        }
    }
}
