package dev.materione.behavior.expr;

import java.util.Objects;

/**
 * The tree-walk evaluator. Typed values: {@code Double} (all numbers),
 * {@code Boolean}, {@code String}, {@code null}. Coercions are explicit and
 * documented per operator — an impossible coercion throws
 * {@link ExpressionException}, which the engine degrades to a rate-limited
 * diagnostic (never a crash, runtime-systems §2.1).
 */
public final class Expressions {

    private Expressions() {}

    public static Object evaluate(ExprNode node, EvaluationContext context) {
        if (node instanceof ExprNode.Num num) {
            return num.value();
        }
        if (node instanceof ExprNode.Str str) {
            return str.value();
        }
        if (node instanceof ExprNode.Bool bool) {
            return bool.value();
        }
        if (node instanceof ExprNode.Null) {
            return null;
        }
        if (node instanceof ExprNode.Path path) {
            return context.resolve(path.segments());
        }
        if (node instanceof ExprNode.Unary unary) {
            return unary(unary, context);
        }
        if (node instanceof ExprNode.Ternary ternary) {
            return truthy(evaluate(ternary.condition(), context))
                    ? evaluate(ternary.ifTrue(), context)
                    : evaluate(ternary.ifFalse(), context);
        }
        if (node instanceof ExprNode.Binary binary) {
            return binary(binary, context);
        }
        throw new ExpressionException("unknown expression node " + node.getClass());
    }

    private static Object unary(ExprNode.Unary unary, EvaluationContext context) {
        Object value = evaluate(unary.operand(), context);
        if (unary.operator().equals("!")) {
            return !truthy(value);
        }
        return -requireNumber(value, "unary -");
    }

    private static Object binary(ExprNode.Binary binary, EvaluationContext context) {
        String op = binary.operator();
        // short-circuit before evaluating the right side
        if (op.equals("&&")) {
            return truthy(evaluate(binary.left(), context))
                    ? truthy(evaluate(binary.right(), context))
                    : Boolean.FALSE;
        }
        if (op.equals("||")) {
            return truthy(evaluate(binary.left(), context))
                    ? Boolean.TRUE
                    : truthy(evaluate(binary.right(), context));
        }
        Object left = evaluate(binary.left(), context);
        Object right = evaluate(binary.right(), context);
        return switch (op) {
            case "+" -> plus(left, right);
            case "-", "*", "/", "%" -> arithmetic(op, left, right);
            case "==", "!=" -> compareEquality(op, left, right);
            case "<", "<=", ">", ">=" -> compareOrder(op, left, right);
            default -> throw new ExpressionException("unsupported operator '" + op + "'");
        };
    }

    /** {@code +}: string concatenation when either side is a string, numeric addition otherwise. */
    private static Object plus(Object left, Object right) {
        if (left instanceof String || right instanceof String) {
            return stringify(left) + stringify(right);
        }
        return requireNumber(left, "+") + requireNumber(right, "+");
    }

    private static Object arithmetic(String op, Object left, Object right) {
        double a = requireNumber(left, op);
        double b = requireNumber(right, op);
        return switch (op) {
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> b == 0 ? null : a / b;      // divide by zero is data, not a crash
            case "%" -> b == 0 ? null : a % b;
            default -> throw new ExpressionException("unsupported arithmetic '" + op + "'");
        };
    }

    private static Boolean compareEquality(String op, Object left, Object right) {
        boolean equal;
        if (left instanceof Number && right instanceof Number) {
            equal = ((Number) left).doubleValue() == ((Number) right).doubleValue();
        } else {
            equal = Objects.equals(stringify(left), stringify(right));
        }
        return op.equals("==") == equal;
    }

    private static Boolean compareOrder(String op, Object left, Object right) {
        int comparison;
        if (left instanceof Number && right instanceof Number) {
            comparison = Double.compare(
                    ((Number) left).doubleValue(), ((Number) right).doubleValue());
        } else {
            comparison = stringify(left).compareTo(stringify(right));
        }
        return switch (op) {
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            default -> throw new ExpressionException("unsupported comparison '" + op + "'");
        };
    }

    /**
     * Truthiness: {@code null}/false → false, numbers → non-zero, strings →
     * non-empty and not {@code "false"}, anything else → true.
     */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof String text) {
            return !text.isEmpty() && !"false".equalsIgnoreCase(text);
        }
        return true;
    }

    private static double requireNumber(Object value, String operator) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new ExpressionException(
                "operator '" + operator + "' needs numbers, got "
                        + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    public static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
            return String.valueOf(d.longValue()); // 20.0 renders as 20 in interpolations
        }
        return String.valueOf(value);
    }
}
