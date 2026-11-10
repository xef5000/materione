package dev.materione.plugin.escrow.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The valid records of one player's ledger, in file order (gui-input-durability.md §5.3). Immutable. A player who
 * never consumed has {@link #EMPTY}.
 */
public final class Ledger {

    /** No records. */
    public static final Ledger EMPTY = new Ledger(List.of());

    private final List<LedgerRecord> records;
    private final Set<Id128> intentEntries = new HashSet<>();
    private final Set<Id128> intentTxns = new HashSet<>();
    private final Set<Id128> doneTxns = new HashSet<>();

    /** A ledger of these records. */
    public Ledger(List<LedgerRecord> records) {
        this.records = List.copyOf(records);
        for (LedgerRecord record : this.records) {
            switch (record) {
                case LedgerRecord.Intent intent -> {
                    intentEntries.add(intent.entryId());
                    intentTxns.add(intent.txn());
                }
                case LedgerRecord.Done done -> doneTxns.add(done.txn());
            }
        }
    }

    /** The records, in file order. */
    public List<LedgerRecord> records() {
        return records;
    }

    /** A durable exit decision exists for this entry (the question recovery asks, §5.7). */
    public boolean hasIntent(Id128 entryId) {
        return intentEntries.contains(entryId);
    }

    /** The transaction's INTENT is durable. */
    public boolean isDecided(Id128 txn) {
        return intentTxns.contains(txn);
    }

    /** The transaction's DONE is durable. */
    public boolean isDone(Id128 txn) {
        return doneTxns.contains(txn);
    }

    /** The INTENT of this entry. */
    public Optional<LedgerRecord.Intent> intentFor(Id128 entryId) {
        for (LedgerRecord record : records) {
            if (record instanceof LedgerRecord.Intent intent && intent.entryId().equals(entryId)) {
                return Optional.of(intent);
            }
        }
        return Optional.empty();
    }

    /** This ledger after {@code record} is appended (the in-memory image of a completed file append). */
    public Ledger appended(LedgerRecord record) {
        var next = new ArrayList<>(records);
        next.add(record);
        return new Ledger(next);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Ledger that && records.equals(that.records);
    }

    @Override
    public int hashCode() {
        return records.hashCode();
    }

    @Override
    public String toString() {
        return "Ledger" + records;
    }
}
