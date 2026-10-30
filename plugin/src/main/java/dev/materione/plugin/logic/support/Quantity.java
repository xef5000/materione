package dev.materione.plugin.logic.support;

import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.TemplateText;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * A numeric parameter that may be written as a number ({@code amount: 5}) or as a template that
 * renders to one ({@code amount: "${vars.price}"}). A literal is checked against its range at load;
 * a template is checked each time it renders, and a value that is not a number in range makes the
 * step fail rather than guess (the strict-reads rule of {@link ParamReader}).
 *
 * <p>A {@link #whole} quantity is a count: a fractional literal is rejected at load, and a template
 * that renders fractional fails the step.
 */
public record Quantity(double literal, TemplateText template, double min, double max, boolean whole) {

    /** A required decimal quantity. */
    public static Quantity required(ParamReader params, String key, double min, double max) {
        return require(params, key, min, max, false);
    }

    /** An optional decimal quantity; empty when the author did not write it. */
    public static Optional<Quantity> optional(ParamReader params, String key, double min, double max) {
        return params.raw(key).isEmpty() ? Optional.empty() : Optional.of(read(params, key, min, max, false));
    }

    /** A required count (a whole number). */
    public static Quantity requiredCount(ParamReader params, String key, int min, int max) {
        return require(params, key, min, max, true);
    }

    /** An optional count (a whole number); empty when the author did not write it. */
    public static Optional<Quantity> optionalCount(ParamReader params, String key, int min, int max) {
        return params.raw(key).isEmpty() ? Optional.empty() : Optional.of(read(params, key, min, max, true));
    }

    private static Quantity require(ParamReader params, String key, double min, double max, boolean whole) {
        if (params.raw(key).isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return read(params, key, min, max, whole);
    }

    private static Quantity read(ParamReader params, String key, double min, double max, boolean whole) {
        Object raw = params.raw(key).orElseThrow();
        String range = whole ? (long) min + ".." + (long) max : min + ".." + max;
        String kind = whole ? "a whole number in " : "a number in ";
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (!Double.isFinite(value) || value < min || value > max
                    || (whole && value != Math.rint(value))) {
                throw new IllegalArgumentException(key + " must be " + kind + range);
            }
            return new Quantity(value, null, min, max, whole);
        }
        if (!(raw instanceof String text) || (!text.contains("${") && !text.contains("%"))) {
            throw new IllegalArgumentException(key + " must be a number or a template that renders to one");
        }
        return new Quantity(0, params.template(key), min, max, whole);
    }

    /** The number this run sees, or empty when a template did not render to a number in range. */
    public OptionalDouble value(LogicContext context) {
        if (template == null) {
            return OptionalDouble.of(literal);
        }
        try {
            double value = Double.parseDouble(template.render(context).trim());
            return Double.isFinite(value) && value >= min && value <= max
                    ? OptionalDouble.of(value)
                    : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    /** The number as a whole number in {@code int} range, or empty when it is fractional or missing. */
    public OptionalInt wholeValue(LogicContext context) {
        OptionalDouble value = value(context);
        if (value.isEmpty()) {
            return OptionalInt.empty();
        }
        double number = value.getAsDouble();
        return number == Math.rint(number) && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
                ? OptionalInt.of((int) number)
                : OptionalInt.empty();
    }
}
