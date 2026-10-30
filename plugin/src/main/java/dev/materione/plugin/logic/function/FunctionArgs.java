package dev.materione.plugin.logic.function;

import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.behavior.expr.Expressions;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The argument readers the built-in expression functions share (logic.md §4.2). They are the only
 * place a function decides what an argument means, so the rules below hold for every function:
 *
 * <ul>
 *   <li><b>Text</b> is a string; a number or a boolean counts as the text the engine shows in an
 *       interpolation ({@code 20} is {@code "20"}). Anything else (null, a list, a map) is not text.
 *   <li><b>A number</b> is a finite number, or a string that reads as one ({@code " 12.5 "},
 *       {@code "-3e2"}). A boolean is never a number and {@code null} is never a number.
 * </ul>
 *
 * <p>A function never throws on a bad argument: a predicate answers {@code false}, a value function
 * answers {@code null}, and the expression goes on (runtime-systems §2.1).
 */
final class FunctionArgs {

    /** The version the built-in functions first appear in (the logic vocabulary of L3). */
    static final String SINCE = "2.0.0";

    /** Plain decimal text: an optional sign, digits with an optional fraction or a leading dot, an optional exponent. */
    private static final Pattern NUMBER_TEXT =
            Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");

    private FunctionArgs() {}

    /** The text an argument reads as, or empty when it is not text. */
    static Optional<String> text(Object value) {
        if (value instanceof String string) {
            return Optional.of(string);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return Optional.of(Expressions.stringify(value));
        }
        return Optional.empty();
    }

    /** The finite number an argument reads as, or empty when it is not one. */
    static Optional<Double> number(Object value) {
        if (value instanceof Number number) {
            return finite(number.doubleValue());
        }
        if (value instanceof String string) {
            String trimmed = string.strip();
            if (!NUMBER_TEXT.matcher(trimmed).matches()) {
                return Optional.empty();
            }
            return finite(Double.parseDouble(trimmed));
        }
        return Optional.empty();
    }

    /** True when {@code value} is a whole number. */
    static boolean whole(double value) {
        return value == Math.rint(value);
    }

    /** A parameter with a description and no default, for a self-description. */
    static ParameterSchema param(String id, ParameterType type, boolean required, String description) {
        return new ParameterSchema(id, type, required, null, description, null, null);
    }

    private static Optional<Double> finite(double value) {
        return Double.isFinite(value) ? Optional.of(value) : Optional.empty();
    }
}
