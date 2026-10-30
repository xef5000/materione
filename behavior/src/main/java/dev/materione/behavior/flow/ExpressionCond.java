package dev.materione.behavior.flow;

import dev.materione.api.logic.LogicContext;
import dev.materione.behavior.expr.ExprNode;
import dev.materione.behavior.expr.Expressions;
import dev.materione.behavior.logic.EngineExpressions;

/**
 * An expression condition: the one expression engine evaluates {@code tree} and the result is
 * truth-tested ({@link Expressions#truthy}). Reads only the run's own variable tree.
 *
 * @param source the authored text (a surrounding {@code ${...}} is already removed)
 * @param tree the parsed expression
 */
public record ExpressionCond(String source, ExprNode tree) implements Condition {

    @Override
    public boolean test(LogicContext context) {
        return Expressions.truthy(Expressions.evaluate(tree, EngineExpressions.evaluationOf(context)));
    }

    @Override
    public boolean readsLiveState() {
        return false;
    }

    @Override
    public String describe() {
        return source;
    }
}
