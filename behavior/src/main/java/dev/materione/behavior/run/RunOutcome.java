package dev.materione.behavior.run;

/** How a run ended. */
public enum RunOutcome {

    /** Every step ran (some actions may have failed; that does not end a run). */
    FINISHED,

    /** A {@code stop} step ended it. */
    STOPPED,

    /** A {@code require} failed, its {@code else} steps ran, and the run ended. */
    REQUIRE_FAILED,

    /** The run budget ended it (steps, call depth, parked runs, or no way to schedule it). */
    ABORTED,

    /** {@link FlowInterpreter#cancel} ended it (a closed GUI session, a left player). */
    CANCELLED,

    /** It was parked and the platform could no longer resume it (the player disconnected). */
    DROPPED
}
