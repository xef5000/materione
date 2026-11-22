package dev.materione.api.logic;

/**
 * A per-run tally an action charges against a cap (logic.md §41): how many blocks one run has broken, how many
 * particles it has spawned, how many projectiles it has launched. The engine gives each run one meter, so a flow
 * that repeats a step (a {@code call} in a chain, a long {@code do:}) is bounded as a whole, not per step.
 *
 * <p>It is a facet ({@link #FACET}) because an action reads everything about its run from the context; a context that
 * is not a run (a unit test calling an action directly) carries none, and the action then enforces only its own
 * per-step bound. A meter belongs to one run, is touched by one thread at a time, and resets nothing: a run that is
 * parked and resumes keeps its totals.
 */
public interface RunMeter {

    /** The run's meter; absent for a context that is not a run. */
    ContextFacet<RunMeter> FACET = ContextFacet.of("materione:run_meter", RunMeter.class);

    /**
     * Adds {@code amount} to the total kept under {@code key} (a verb names its own key) and returns the new total.
     * The caller compares it with its cap; a charge is not refunded when the step fails afterwards, which is the
     * conservative direction for a budget.
     */
    long add(String key, long amount);

    /** The total charged under {@code key} so far. */
    long total(String key);

    /**
     * An opaque identity of the rule this run executes, or null when it has none. A verb that raises server events names it as
     * the cause, so the bridges do not answer the event with the very rule that caused it (logic.md §41).
     */
    default Object origin() {
        return null;
    }
}
