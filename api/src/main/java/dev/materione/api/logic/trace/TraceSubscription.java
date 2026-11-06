package dev.materione.api.logic.trace;

/**
 * A live subscription to run traces (logic.md §7.6). Closing it is how a subscriber unsubscribes, and it is
 * idempotent. The host also closes every subscription when it shuts down.
 */
public interface TraceSubscription extends AutoCloseable {

    /** The filter this subscription was made with. */
    TraceFilter filter();

    /** False once the subscription was closed, by the subscriber or by the host. */
    boolean active();

    /** Unsubscribes. Idempotent; never throws. */
    @Override
    void close();
}
