package dev.materione.api.logic.variable;

import java.util.Locale;
import java.util.Optional;

/**
 * The operations {@code set_var} applies to a variable (logic.md §3.3). Arithmetic is on numbers,
 * {@link #TOGGLE} is on booleans, and the rest work on any type.
 */
public enum VariableOp {
    /** Replaces the value with the operand. */
    SET(true),
    /** Adds the operand to a number. */
    ADD(true),
    /** Subtracts the operand from a number. */
    SUBTRACT(true),
    /** Multiplies a number by the operand. */
    MULTIPLY(true),
    /** Negates a boolean. */
    TOGGLE(false),
    /** Clears the stored value: a read returns the default again (local: the name is removed). */
    REMOVE(false),
    /** Sets the declared default (local: same as {@link #REMOVE}). */
    RESET(false);

    private final boolean takesValue;

    VariableOp(boolean takesValue) {
        this.takesValue = takesValue;
    }

    /** True when the step must carry a {@code value:}. */
    public boolean takesValue() {
        return takesValue;
    }

    /** The authored spelling, lowercase. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The operation an authored word names, case-insensitively. */
    public static Optional<VariableOp> of(String word) {
        if (word == null) {
            return Optional.empty();
        }
        for (VariableOp op : values()) {
            if (op.id().equals(word.trim().toLowerCase(Locale.ROOT))) {
                return Optional.of(op);
            }
        }
        return Optional.empty();
    }
}
