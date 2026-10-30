package dev.materione.api.scheduling;

/**
 * A task queued on the {@link SchedulingGateway} that has not run yet. Cancelling it is safe from
 * any thread, idempotent, and a no-op once the task has started.
 */
@FunctionalInterface
public interface ScheduledHandle {

    /** A handle for work that already ran or was never queued. */
    ScheduledHandle NONE = () -> {};

    /** Drops the task if it has not started. */
    void cancel();
}
