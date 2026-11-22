package dev.materione.api.logic;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A numeric parameter that may be written as a number ({@code amount: 5}) or as a template that
 * renders to one ({@code amount: "${vars.price}"}). A literal is checked against its range at load;
 * a template is checked each time it renders, and a value that is not a number in range makes the
 * step fail rather than guess (the strict-reads rule of {@link ParamReader}).
 *
 * <p>A {@link #whole} quantity is a count: a fractional literal is rejected at load, and a template
 * that renders fractional fails the step.
 *
 * <p>The type lives in {@code api} because every layer reads it: the actions and conditions of the
 * plugin (logic.md section 42), and the flow compiler and interpreter for the step keys {@code chance},
 * {@code delay} and {@code wait} (logic.md section 42, #470). A literal read through {@link #integerOr},
 * {@link #decimalOr} or {@link #requiredInteger} keeps the message its {@link ParamReader} read always gave.
 */
public record Quantity(double literal, TemplateText template, double min, double max, boolean whole) {

    private record Resolved(String source) implements TemplateText {
        @Override public String render(LogicContext context) { return source; }
    }

    /** A quantity written as a number in the step, already checked by its caller. */
    public static Quantity constant(double value, double min, double max, boolean whole) {
        return new Quantity(value, null, min, max, whole);
    }

    /** True when the author wrote a template, so the value is read when the step runs. */
    public boolean isTemplate() {
        return template != null;
    }

    /** Resolves a dynamic quantity once, leaving normal numeric validation to {@link #value}. */
    public CompletionStage<Quantity> resolveResumable(LogicContext context, ReadExecutor reads) {
        return template == null ? CompletableFuture.completedFuture(this)
                : template.renderResumable(context, reads)
                        .thenApply(text -> new Quantity(literal, new Resolved(text), min, max, whole));
    }

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

    /**
     * An optional count that defaults to {@code fallback}. A literal is read by {@link ParamReader#integer}, so its
     * load message is the one the parameter always had; a template is read as a count and checked as it renders.
     */
    public static Quantity integerOr(ParamReader params, String key, int fallback, int min, int max) {
        if (params.raw(key).isEmpty()) {
            return constant(fallback, min, max, true);
        }
        if (isTemplateText(params, key)) {
            return new Quantity(0, params.template(key), min, max, true);
        }
        return constant(params.integer(key, fallback, min, max), min, max, true);
    }

    /** A required count; a literal is read by {@link ParamReader#requiredInteger}, as the parameter always was. */
    public static Quantity requiredInteger(ParamReader params, String key, int min, int max) {
        if (params.raw(key).isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        if (isTemplateText(params, key)) {
            return new Quantity(0, params.template(key), min, max, true);
        }
        return constant(params.requiredInteger(key, min, max), min, max, true);
    }

    /** An optional decimal that defaults to {@code fallback}; a literal is read by {@link ParamReader#decimal}. */
    public static Quantity decimalOr(ParamReader params, String key, double fallback, double min, double max) {
        if (params.raw(key).isEmpty()) {
            return constant(fallback, min, max, false);
        }
        if (isTemplateText(params, key)) {
            return new Quantity(0, params.template(key), min, max, false);
        }
        return constant(params.decimal(key, fallback, min, max), min, max, false);
    }

    /** True when the parameter is a string holding a template, the one shape a quantity reads as an expression. */
    static boolean isTemplateText(ParamReader params, String key) {
        return params.raw(key).orElse(null) instanceof String text && (text.contains("${") || text.contains("%"));
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

    /**
     * The reason a step gives when its template did not render to a value in range (logic.md section 42, MTR-0120): the
     * parameter, its range and what the template rendered to, so the author sees the value that was refused.
     */
    public String refusal(String key, LogicContext context) {
        String shown = template == null ? Double.toString(literal) : template.render(context);
        String range = whole ? (long) min + ".." + (long) max : min + ".." + max;
        return key + " must be " + (whole ? "a whole number" : "a number") + " in " + range + ", not \"" + shown + "\"";
    }
}
