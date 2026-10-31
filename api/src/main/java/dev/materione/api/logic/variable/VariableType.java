package dev.materione.api.logic.variable;

import java.util.Locale;
import java.util.Optional;

/**
 * The type a declared variable holds (logic.md §3.2). Three kinds, closed: a number is always a
 * finite {@code double}, so arithmetic has one representation.
 */
public enum VariableType {
    NUMBER,
    STRING,
    BOOLEAN;

    /** The authored spelling ({@code number}, {@code string}, {@code boolean}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The type an authored word names, or empty for anything else. */
    public static Optional<VariableType> of(String word) {
        if (word == null) {
            return Optional.empty();
        }
        for (VariableType type : values()) {
            if (type.id().equals(word.trim().toLowerCase(Locale.ROOT))) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** The value a declaration without {@code default:} holds: {@code 0}, empty text, {@code false}. */
    public Object defaultValue() {
        return switch (this) {
            case NUMBER -> 0.0d;
            case STRING -> "";
            case BOOLEAN -> Boolean.FALSE;
        };
    }

    /**
     * True when {@code literal} already has this type's Java representation, with no conversion. Used
     * for values written in the file: the author's {@code "5"} is not a number literal.
     */
    public boolean accepts(Object literal) {
        return switch (this) {
            case NUMBER -> literal instanceof Number number && Double.isFinite(number.doubleValue());
            case STRING -> literal instanceof String;
            case BOOLEAN -> literal instanceof Boolean;
        };
    }

    /**
     * Converts a value computed at run time (a template's text, an expression's result) to this type,
     * or empty when it does not fit. Unlike {@link #accepts}, text that reads as a number is converted,
     * because a template always produces text.
     */
    public Optional<Object> coerce(Object value) {
        return switch (this) {
            case NUMBER -> coerceNumber(value);
            case STRING -> value instanceof String text ? Optional.of(text) : Optional.empty();
            case BOOLEAN -> coerceBoolean(value);
        };
    }

    private static Optional<Object> coerceNumber(Object value) {
        double number;
        if (value instanceof Number given) {
            number = given.doubleValue();
        } else if (value instanceof String text) {
            try {
                number = Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }
        return Double.isFinite(number) ? Optional.of(number) : Optional.empty();
    }

    private static Optional<Object> coerceBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return Optional.of(bool);
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
                return Optional.of(Boolean.parseBoolean(trimmed));
            }
        }
        return Optional.empty();
    }
}
