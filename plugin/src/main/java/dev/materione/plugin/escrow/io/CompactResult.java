package dev.materione.plugin.escrow.io;

import java.util.Objects;

/**
 * What a compaction did (gui-input-durability.md §5.3). Compaction is fail-soft: it never throws, and a failure
 * leaves the previous file whole.
 *
 * @param outcome what happened
 * @param dropped how many records the rewrite dropped ({@link Outcome#COMPACTED} only)
 * @param detail  the cause of a {@link Outcome#FAILED} or {@link Outcome#SKIPPED}, else empty
 */
public record CompactResult(Outcome outcome, int dropped, String detail) {

    public CompactResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(detail, "detail");
    }

    /** The result of a rewrite. */
    static CompactResult compacted(int dropped) {
        return new CompactResult(Outcome.COMPACTED, dropped, "");
    }

    /** Nothing was droppable. */
    static CompactResult nothingToDo() {
        return new CompactResult(Outcome.NOTHING_TO_DO, 0, "");
    }

    /** Not attempted. */
    static CompactResult skipped(String why) {
        return new CompactResult(Outcome.SKIPPED, 0, why);
    }

    /** Attempted and failed; the old file is intact. */
    static CompactResult failed(String why) {
        return new CompactResult(Outcome.FAILED, 0, why);
    }

    /** The outcome. */
    public enum Outcome {
        /** The file was rewritten atomically without the dropped records. */
        COMPACTED,
        /** Every record is still needed (or inside the retention). */
        NOTHING_TO_DO,
        /** The player's ledger is unrecoverable, or the store is closed. */
        SKIPPED,
        /** The rewrite failed; the previous file is whole and a later compaction retries. */
        FAILED
    }
}
