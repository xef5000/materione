package dev.materione.api.logic.trace;

/**
 * The caps of the live trace (logic.md §32). A trace is a debugging aid, so every limit errs towards dropping
 * and saying so, never towards slowing a run or growing without bound.
 *
 * @param eventsPerSecond entries one subscription accepts per second (a token bucket whose capacity is one
 *        second's worth, so a click's worth of steps goes through whole)
 * @param maxEntriesPerRun step entries one subscription keeps for one run; the run's start and end entries
 *        are exempt, so a run that hits the cap still shows how it ended
 * @param maxPending entries one subscription holds between deliveries; past it entries are dropped
 * @param maxBatch entries one delivery carries; the rest wait for the next one
 * @param maxSubscriptions subscriptions the server holds at once
 * @param maxDetail characters of an entry's detail kept; a longer one is cut with an ellipsis
 */
public record TraceLimits(
        int eventsPerSecond, int maxEntriesPerRun, int maxPending, int maxBatch, int maxSubscriptions,
        int maxDetail) {

    /** 200 entries a second, 256 steps a run, 512 pending, 128 a delivery, 8 subscriptions, 160-character details. */
    public static final TraceLimits DEFAULT = new TraceLimits(200, 256, 512, 128, 8, 160);

    public TraceLimits {
        if (eventsPerSecond < 1 || maxEntriesPerRun < 1 || maxPending < 1 || maxBatch < 1 || maxSubscriptions < 1
                || maxDetail < 8) {
            throw new IllegalArgumentException("every trace limit must be positive (a detail at least 8)");
        }
    }
}
