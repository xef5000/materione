package dev.materione.behavior.logic;

import dev.materione.behavior.expr.EvaluationContext;

/**
 * A {@link dev.materione.api.logic.LogicContext} that already holds the expression engine's view of
 * its variable tree, so evaluating an expression against it allocates nothing. The engine's
 * {@link MapLogicContext} and the interpreter's per-run wrapper implement it.
 */
public interface EvaluationAware {

    /** The expression-visible variable tree of this context. */
    EvaluationContext evaluation();
}
