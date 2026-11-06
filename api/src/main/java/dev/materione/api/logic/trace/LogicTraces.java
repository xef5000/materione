package dev.materione.api.logic.trace;

import dev.materione.api.ApiResult;

/**
 * Live run traces (logic.md §7.6, §32; reached as {@code api.logic().traces()}): subscribe to the step-boundary
 * events of the runs a filter selects, and hear them in batches.
 *
 * <pre>{@code
 * var filter = TraceFilter.resource("shop:main", player.getUniqueId());
 * ApiResult<TraceSubscription> sub = api.logic().traces().subscribe(filter, batch -> render(batch));
 * // later
 * sub.value().close();
 * }</pre>
 *
 * <p>A trace costs nothing while nobody subscribes: the engine builds no events. A subscription is rate-limited
 * and capped (events per second, entries per run, pending entries, concurrent subscriptions); what exceeds a
 * limit is dropped and reported as a count on the next batch, and the engine is never slowed or failed by a
 * subscriber. Nothing here edits files (D1) or runs a flow (D6).
 */
public interface LogicTraces {

    /**
     * Subscribes {@code listener} to the runs {@code filter} selects.
     *
     * @return the subscription, or a refusal ({@code MTR-0089}) when the server's concurrent-subscription cap is reached
     */
    ApiResult<TraceSubscription> subscribe(TraceFilter filter, TraceListener listener);

    /** The caps every subscription lives under, so a subscriber can size what it does with a batch. */
    TraceLimits limits();

    /** How many subscriptions are open now. */
    int subscriptions();
}
