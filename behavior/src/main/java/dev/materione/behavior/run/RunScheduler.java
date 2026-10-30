package dev.materione.behavior.run;

import dev.materione.api.logic.Threading;

/**
 * The interpreter's only dependency on the platform's threads (logic.md §5.2, plugin.md §2): where
 * a step may run, and how a parked run comes back. The plugin implements it over the thread
 * gateway (player region, global region); a test implements it with a queue and drives time by hand,
 * which is how the interpreter is unit-tested without a server.
 *
 * <p>The interpreter never blocks: to wait, hop or delay it hands the rest of the run to
 * {@link #schedule} and returns. A run is therefore a value that moves between threads, not a thread
 * that sleeps.
 */
public interface RunScheduler {

    /**
     * Whether the calling thread may run a step that needs {@code thread} for the run anchored at
     * {@code anchor}. {@link Threading#ANY} is always true. On Paper there is one thread and every
     * answer is true; on Folia the answer is the region/global ownership check. A step for which
     * this is true runs inline, which keeps a run on one thread (and an event still cancellable)
     * for as long as it can.
     *
     * @param anchor the run's thread-locality hint: the player entity, or null when none is known
     */
    boolean canRunInline(Object anchor, Threading thread);

    /**
     * Runs {@code task} on a thread that satisfies {@code thread} for {@code anchor}, after
     * {@code delayTicks} (0 = as soon as that thread is next free, never inline). When the platform
     * can no longer run it (the player left before the task came due) {@code dropped} runs
     * instead.
     *
     * @return a handle that cancels the task while it is still queued
     */
    Handle schedule(Object anchor, Threading thread, long delayTicks, Runnable task, Runnable dropped);

    /** A queued task. Cancelling is safe from any thread and idempotent. */
    @FunctionalInterface
    interface Handle {

        /** A handle for work that is not queued. */
        Handle NONE = () -> {};

        void cancel();
    }
}
