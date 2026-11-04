package dev.materione.api.logic;

import dev.materione.api.registry.ParameterType;
import java.util.Optional;

/**
 * The kinds of a recipe parameter (logic.md §4.6, §30). An expansion receives each parameter already typed:
 * text as {@link String}, a number as {@link Double}, a whole number as {@link Integer}, a flag as {@link Boolean},
 * and a variable as its {@code pack:key} text.
 */
public enum RecipeParamType {

    /** Text. A required text parameter must not be blank; an optional one may be blank, and a template may drop its key. */
    STRING,

    /** A finite decimal number, optionally within a {@code min}..{@code max} range. */
    NUMBER,

    /** A whole number, optionally within a {@code min}..{@code max} range. */
    INTEGER,

    /** {@code true} or {@code false}. */
    BOOLEAN,

    /**
     * A declared persistent variable, written {@code pack:key}. Its declaration must have the recipe's scope and
     * value type, so an expansion that writes it always validates.
     */
    VARIABLE;

    /** The editor's name for the kind, as the vocabulary writes it. */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The kind's value in the typed form an expansion receives, or empty when {@code raw} does not fit it. */
    public Optional<Object> coerce(Object raw) {
        return switch (this) {
            case STRING, VARIABLE -> raw instanceof String text ? Optional.of(text) : Optional.empty();
            case NUMBER -> raw instanceof Number number && Double.isFinite(number.doubleValue())
                    ? Optional.of(number.doubleValue())
                    : Optional.empty();
            case INTEGER -> wholeNumber(raw);
            case BOOLEAN -> raw instanceof Boolean flag ? Optional.of(flag) : Optional.empty();
        };
    }

    /** The registry schema kind the editor's form reads for this parameter kind. */
    ParameterType schemaType() {
        return switch (this) {
            case STRING -> ParameterType.STRING;
            case NUMBER -> ParameterType.DECIMAL;
            case INTEGER -> ParameterType.INTEGER;
            case BOOLEAN -> ParameterType.BOOLEAN;
            case VARIABLE -> ParameterType.REFERENCE;
        };
    }

    private static Optional<Object> wholeNumber(Object raw) {
        if (!(raw instanceof Number number)) {
            return Optional.empty();
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        return Optional.of((int) value);
    }
}
