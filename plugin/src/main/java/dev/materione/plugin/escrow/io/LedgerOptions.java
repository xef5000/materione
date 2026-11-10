package dev.materione.plugin.escrow.io;

import java.time.Duration;

/**
 * The ledger writer's settings (gui-input-durability.md §5.3).
 *
 * @param fsync           {@code force(false)} after every append and before a compaction's rename. Helps against
 *                        power loss only (F11: a completed write survives a process kill without it); affordable
 *                        because it runs on the IO executor, never on a region thread (§5.6)
 * @param retentionMillis how long an acknowledged exit's INTENT is kept before compaction may drop it
 */
public record LedgerOptions(boolean fsync, long retentionMillis) {

    /** {@code escrow.retention} default: 30 days (owner-confirmed, §14 Q6). */
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(30);

    /** The shipped defaults: {@code fsync} on, 30 days. */
    public static final LedgerOptions DEFAULTS = new LedgerOptions(true, DEFAULT_RETENTION.toMillis());

    /** @throws IllegalArgumentException on a negative retention */
    public LedgerOptions {
        if (retentionMillis < 0) {
            throw new IllegalArgumentException("retention must not be negative");
        }
    }

    /** These options with another retention. */
    public LedgerOptions withRetention(Duration retention) {
        return new LedgerOptions(fsync, retention.toMillis());
    }

    /** These options with another fsync setting. */
    public LedgerOptions withFsync(boolean value) {
        return new LedgerOptions(value, retentionMillis);
    }
}
