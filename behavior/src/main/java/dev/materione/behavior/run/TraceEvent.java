package dev.materione.behavior.run;

import dev.materione.behavior.flow.StepPath;
import java.util.Objects;

/**
 * One observable moment of a run (logic.md §5.2, §7.6): a step boundary, a decision, a suspension.
 * The studio's live trace lights up the executed edges from these, and a failed action shows its
 * reason on its node. Events are emitted only when a {@link TraceSink} is installed, so a server
 * nobody is debugging pays for none of them.
 *
 * @param runId the run's id, unique per interpreter
 * @param rule the rule's id
 * @param path the step the event is about ({@code behavior.2.do.0.then.1}); the rule's own path
 *        for run-level events
 * @param type what happened
 * @param detail a short human sentence, or an empty string
 */
public record TraceEvent(long runId, String rule, StepPath path, Type type, String detail) {

    /** What happened. */
    public enum Type {
        /** The run began. */
        RUN_STARTED,
        /** An action ran and succeeded. */
        STEP_OK,
        /** An action failed or threw; {@code detail} is the reason. */
        STEP_FAILED,
        /** A step did not run (a {@code chance:} roll missed). */
        STEP_SKIPPED,
        /** An {@code if} or {@code choose} picked a branch; {@code detail} says which. */
        BRANCH,
        /** A {@code require} failed and ended the run after its {@code else} steps. */
        REQUIRE_FAILED,
        /** A {@code call} entered a flow. */
        CALLED,
        /** The run parked ({@code wait}, {@code delay:} or a thread hop). */
        SUSPENDED,
        /** The run resumed. */
        RESUMED,
        /** The run ended; {@code detail} is the {@link RunOutcome}. */
        RUN_ENDED
    }

    public TraceEvent {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        detail = detail == null ? "" : detail;
    }
}
