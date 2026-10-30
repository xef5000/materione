package dev.materione.behavior.flow;

import dev.materione.api.logic.LogicContext;

/**
 * A compiled condition tree (logic.md §2.3, §5.1): an expression, a registered predicate, or a
 * composite of those. Compilation happens once at load; {@link #test} is the only thing the hot
 * path calls.
 *
 * <p><b>Laziness.</b> The composites evaluate left to right and stop as soon as the answer is
 * known ({@code all} at the first false, {@code any} at the first true, {@code at_least} as soon as
 * the count is reached or can no longer be), so a cheap check placed first guards an expensive one
 * and {@code stop_at_success} comes for free.
 *
 * <p><b>Failure.</b> A leaf that throws (an expression type error, a predicate that blew up) makes
 * {@link #test} throw. The interpreter contains it and counts the <em>whole</em> condition as
 * false, so a broken check can never grant what it was guarding, not even under {@code not}.
 */
public sealed interface Condition
        permits ExpressionCond, PredicateCond, AllCond, AnyCond, NotCond, AtLeastCond {

    /**
     * Evaluates against {@code context}.
     *
     * @throws RuntimeException when a leaf cannot be evaluated
     */
    boolean test(LogicContext context);

    /**
     * True when a registered predicate occurs anywhere in the tree. Predicates read live player
     * and world state, so the interpreter evaluates such a condition on the player's thread
     * (a pure expression only reads the run's own data and runs wherever the run is).
     */
    boolean readsLiveState();

    /** A short rendering for traces and diagnostics. */
    String describe();
}
