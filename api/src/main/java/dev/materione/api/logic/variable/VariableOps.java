package dev.materione.api.logic.variable;

/**
 * The arithmetic and logic of {@code set_var} (logic.md §3.3), free of any store or context so every
 * operation and every type error is a unit test. Values are coerced the way a run-time value is: text
 * that reads as a number is a number, because a template always yields text.
 */
public final class VariableOps {

    private VariableOps() {}

    /**
     * The result of one operation: the value to store (null clears it), or the reason it was refused.
     *
     * @param ok false when refused
     * @param value the new value, null for a clear
     * @param reason why it was refused; empty when {@code ok}
     */
    public record Outcome(boolean ok, Object value, String reason) {

        static Outcome stored(Object value) {
            return new Outcome(true, value, "");
        }

        static Outcome refused(String reason) {
            return new Outcome(false, null, reason);
        }
    }

    /**
     * Applies {@code op}.
     *
     * @param type the declared type of the variable, or null for a run-local (no declaration to check)
     * @param current the value now held (null for an unset local; a declared variable passes its default)
     * @param operand the operand, already evaluated; null when the operation takes none
     */
    public static Outcome apply(VariableOp op, VariableType type, Object current, Object operand) {
        return switch (op) {
            case SET -> set(type, operand);
            case ADD -> arithmetic(current, operand, (a, b) -> a + b);
            case SUBTRACT -> arithmetic(current, operand, (a, b) -> a - b);
            case MULTIPLY -> arithmetic(current, operand, (a, b) -> a * b);
            case TOGGLE -> toggle(current);
            case REMOVE, RESET -> Outcome.stored(null);
        };
    }

    private static Outcome set(VariableType type, Object operand) {
        if (type == null || operand == null) {
            return Outcome.stored(operand);
        }
        return type.coerce(operand)
                .map(Outcome::stored)
                .orElseGet(() -> Outcome.refused("'" + operand + "' is not a " + type.id()));
    }

    private static Outcome arithmetic(
            Object current, Object operand, java.util.function.DoubleBinaryOperator operator) {
        Object left = current == null ? VariableType.NUMBER.defaultValue() : current;
        var a = VariableType.NUMBER.coerce(left);
        if (a.isEmpty()) {
            return Outcome.refused("the variable holds '" + left + "', which is not a number");
        }
        if (operand == null) {
            return Outcome.refused("arithmetic needs a value");
        }
        var b = VariableType.NUMBER.coerce(operand);
        if (b.isEmpty()) {
            return Outcome.refused("'" + operand + "' is not a number");
        }
        double result = operator.applyAsDouble((Double) a.get(), (Double) b.get());
        return Double.isFinite(result)
                ? Outcome.stored(result)
                : Outcome.refused("the result is not a finite number");
    }

    private static Outcome toggle(Object current) {
        Object held = current == null ? Boolean.FALSE : current;
        var bool = VariableType.BOOLEAN.coerce(held);
        if (bool.isEmpty()) {
            return Outcome.refused("toggle needs a boolean, and the variable holds '" + held + "'");
        }
        return Outcome.stored(!(Boolean) bool.get());
    }
}
