package dev.materione.behavior.run;

import dev.materione.behavior.flow.CallStep;

/**
 * The guards that keep one flow, or one player, from costing the server unboundedly (logic.md
 * §5.3). There is no loop step, so a flow cannot spin on its own; these are the second line.
 *
 * @param maxStepsPerRun steps one run may execute before it is aborted (counts every step visited,
 *        branches and calls included)
 * @param maxParkedPerPlayer runs one player may have parked on a {@code wait} or a {@code delay:}
 *        at once; past it a new run is refused
 * @param maxCallDepth nested {@code call}s one run may be inside at once
 */
public record RunBudget(int maxStepsPerRun, int maxParkedPerPlayer, int maxCallDepth) {

    /** The defaults of logic.md §5.3: 1,000 steps, 64 parked runs, 16 nested calls. */
    public static final RunBudget DEFAULT = new RunBudget(1_000, 64, CallStep.MAX_DEPTH);

    public RunBudget {
        if (maxStepsPerRun < 1 || maxParkedPerPlayer < 1 || maxCallDepth < 1) {
            throw new IllegalArgumentException("every budget limit must be at least 1");
        }
    }
}
