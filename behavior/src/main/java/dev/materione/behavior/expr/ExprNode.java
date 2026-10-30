package dev.materione.behavior.expr;

import dev.materione.api.logic.ExpressionFunction;
import java.util.List;

/**
 * The expression AST (runtime-systems §2.1). Nodes are plain data — the
 * parser produces them once at rule load, the evaluator walks them per fire.
 */
public sealed interface ExprNode {

    record Num(double value) implements ExprNode {}

    record Str(String value) implements ExprNode {}

    record Bool(boolean value) implements ExprNode {}

    record Null() implements ExprNode {}

    /** Dotted variable path ({@code player.health}) resolved through the context. */
    record Path(List<String> segments) implements ExprNode {
        public Path {
            segments = List.copyOf(segments);
        }
    }

    /** {@code !} and unary {@code -}. */
    record Unary(String operator, ExprNode operand) implements ExprNode {}

    /** Arithmetic, comparison, equality and logical binary operators. */
    record Binary(String operator, ExprNode left, ExprNode right) implements ExprNode {}

    /** {@code condition ? then : else}. */
    record Ternary(ExprNode condition, ExprNode ifTrue, ExprNode ifFalse) implements ExprNode {}

    /**
     * {@code name(arguments…)}: a call to a registered function, bound at parse time. {@code name} is
     * the authored spelling (for error text); {@code function} is the resolved type, and the argument
     * count has already been checked against its description.
     */
    record Call(String name, ExpressionFunction function, List<ExprNode> arguments) implements ExprNode {
        public Call {
            arguments = List.copyOf(arguments);
        }
    }
}
