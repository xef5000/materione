package dev.materione.api.logic.trace;

/**
 * Where a subscription's batches go. Called from the host's trace pump, never from the thread that runs a flow,
 * so it may take a moment; it must still return promptly. An exception it throws is contained and the batch is
 * lost.
 */
@FunctionalInterface
public interface TraceListener {

    void accept(TraceBatch batch);
}
