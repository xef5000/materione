package dev.materione.behavior.run;

/**
 * Where {@link TraceEvent}s go. Called on whichever thread the run is on, so an implementation must
 * be cheap and thread-safe (queue and return). An exception it throws is contained and the sink is
 * not retried for that event.
 */
@FunctionalInterface
public interface TraceSink {

    void accept(TraceEvent event);
}
