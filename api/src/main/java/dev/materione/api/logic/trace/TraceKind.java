package dev.materione.api.logic.trace;

/**
 * What a {@link TraceEntry} reports (logic.md §7.6, §32): one observable moment of a run. The names are the
 * wire names the studio reads, lower-cased.
 */
public enum TraceKind {

    /** The run began; the path is the rule's own. */
    RUN_STARTED,
    /** An action ran and succeeded. */
    RAN,
    /** An action failed or threw, or a condition could not be evaluated; the detail is the reason. */
    FAILED,
    /** A step did not run (a {@code chance:} roll missed). */
    SKIPPED,
    /** An {@code if} or {@code choose} picked a branch; the detail says which. */
    BRANCH,
    /** A {@code require} failed and ended the run after its {@code else} steps; the detail is the reason. */
    REQUIRE_FAILED,
    /** A {@code call} entered a flow; the detail is the flow's id. */
    CALLED,
    /** The run parked ({@code wait}, {@code delay:}, a prompt or a thread hop). */
    SUSPENDED,
    /** The run resumed. */
    RESUMED,
    /** The run ended; the detail is its outcome. */
    RUN_ENDED;

    /** The name this kind has on the wire: {@code ran}, {@code require-failed}, and so on. */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
