package dev.materione.behavior.expr;

import java.util.List;
import java.util.Map;

/**
 * A loaded expression: one evaluation tree plus its source text. Parse
 * happens once (rule load); {@link #eval(EvaluationContext)} is the hot path —
 * a plain tree walk over typed values (runtime-systems §2.1: compile-to-lambda
 * is a later optimization, not a design change).
 */
public final class Expression {

    private final String source;
    private final ExprNode tree;

    Expression(String source, ExprNode tree) {
        this.source = source;
        this.tree = tree;
    }

    public String source() {
        return source;
    }

    /** The parsed tree (rule loading embeds condition trees into rules). */
    public ExprNode tree() {
        return tree;
    }

    /** Evaluates against the context; may throw {@link ExpressionException} on type errors. */
    public Object eval(EvaluationContext context) {
        return Expressions.evaluate(tree, context);
    }

    @Override
    public String toString() {
        return source;
    }

    /** The expression-visible variable space: one nested map per namespace ({@code player.*}). */
    public static EvaluationContext contextOf(Map<String, Object> root) {
        return new EvaluationContext(root);
    }
}
