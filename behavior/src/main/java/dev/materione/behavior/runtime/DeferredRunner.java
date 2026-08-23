package dev.materione.behavior.runtime;

/** The engine's only scheduling dependency: how delayed/must-defer actions continue. */
public interface DeferredRunner {

    /**
     * Runs the continuation after {@code delayTicks} (0 = next sane
     * opportunity) on a thread appropriate for {@code anchor} — typically the
     * event's player; null means no locality is known and the platform picks.
     * The platform wires this to the scheduling gateway.
     */
    void defer(Object anchor, long delayTicks, Runnable continuation);
}
