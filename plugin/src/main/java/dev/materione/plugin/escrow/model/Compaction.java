package dev.materione.plugin.escrow.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which ledger records compaction may drop (gui-input-durability.md §5.3, row L): a pure function of the ledger, the
 * clock reading it is given and the retention window.
 *
 * <p>An exit is droppable when its {@code DONE} is durable (the exit was acknowledged, §5.4 step 4) <em>and</em> its
 * {@code INTENT} is older than the retention: until then an older player-data backup restored by an operator could
 * still hold the entry, and the INTENT is what stops recovery returning it (G2). An {@code INTENT} <em>without</em> a
 * {@code DONE} is never dropped, at any age: no acknowledgment means no snapshot is known to have replaced the one
 * that holds the entry, so G2 may not weaken (until the barrier row writes DONE records nothing is droppable, which
 * costs 70 bytes per consume). A {@code DONE} whose INTENT is not in the file is inert and dropped.
 */
public final class Compaction {

    private Compaction() {}

    /**
     * The ledger compaction would write.
     *
     * @param ledger          the valid records as read
     * @param nowMillis       the current epoch millis
     * @param retentionMillis how long an acknowledged exit's INTENT is kept
     * @return {@code ledger} itself (same instance) when nothing is droppable
     */
    public static Ledger plan(Ledger ledger, long nowMillis, long retentionMillis) {
        Set<Id128> intentTxns = new HashSet<>();
        Set<Id128> doneTxns = new HashSet<>();
        for (LedgerRecord record : ledger.records()) {
            switch (record) {
                case LedgerRecord.Intent intent -> intentTxns.add(intent.txn());
                case LedgerRecord.Done done -> doneTxns.add(done.txn());
            }
        }
        long cutoff = nowMillis - retentionMillis;
        Set<Id128> expired = new HashSet<>();
        for (LedgerRecord record : ledger.records()) {
            if (record instanceof LedgerRecord.Intent intent
                    && doneTxns.contains(intent.txn())
                    && intent.atMillis() <= cutoff) {
                expired.add(intent.txn());
            }
        }
        List<LedgerRecord> kept = new ArrayList<>(ledger.records().size());
        for (LedgerRecord record : ledger.records()) {
            boolean drop = switch (record) {
                case LedgerRecord.Intent intent -> expired.contains(intent.txn());
                // a DONE goes with its INTENT; a DONE whose INTENT is not in the file is inert
                case LedgerRecord.Done done -> expired.contains(done.txn()) || !intentTxns.contains(done.txn());
            };
            if (!drop) {
                kept.add(record);
            }
        }
        return kept.size() == ledger.records().size() ? ledger : new Ledger(kept);
    }
}
