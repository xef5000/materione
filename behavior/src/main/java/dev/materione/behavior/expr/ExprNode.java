package dev.materione.behavior.expr;

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
}
