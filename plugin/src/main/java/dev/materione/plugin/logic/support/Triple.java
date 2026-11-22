package dev.materione.plugin.logic.support;

import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Quantity;
import dev.materione.api.logic.ReadExecutor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Three numbers of one parameter, one per axis (logic.md §41): the radii of a {@code break_blocks} shape, the spread of a
 * {@code particle}, the offset of a location. It is written as one number for all three axes ({@code shape: 1}), as a list
 * of three ({@code shape: [1, 1, 0]}), or as a mapping that names the axes ({@code shape: {x: 1, y: "${vars.reach}"}});
 * an axis the mapping leaves out is 0. Each axis is a {@link Quantity}, so a mapping may carry templates; the number and
 * the list are literals, checked at load. The value is read here and nowhere else, so every verb spells it the same way.
 */
public record Triple(Quantity x, Quantity y, Quantity z) {

    /** The three numbers a run sees. */
    public record Values(double x, double y, double z) {}

    private static final Set<String> AXES = Set.of("x", "y", "z");

    /**
     * The parameter {@code key}, or empty when the author did not write it.
     *
     * @param whole whether each axis is a count (a whole number)
     */
    public static Optional<Triple> read(ParamReader params, String key, double min, double max, boolean whole) {
        Optional<Object> raw = params.raw(key);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        Object value = raw.get();
        if (value instanceof Number number) {
            Quantity all = literal(key, number, min, max, whole);
            return Optional.of(new Triple(all, all, all));
        }
        if (value instanceof List<?> list) {
            if (list.size() != 3) {
                throw new IllegalArgumentException(key + " must be one number or three: [x, y, z]");
            }
            for (Object entry : list) {
                if (!(entry instanceof Number)) {
                    throw new IllegalArgumentException(key + " must be one number, three numbers, or {x, y, z}");
                }
            }
            return Optional.of(new Triple(literal(key, (Number) list.get(0), min, max, whole),
                    literal(key, (Number) list.get(1), min, max, whole),
                    literal(key, (Number) list.get(2), min, max, whole)));
        }
        if (value instanceof Map<?, ?>) {
            return Optional.of(readAxes(params.section(key), key, min, max, whole));
        }
        throw new IllegalArgumentException(key + " must be one number, three numbers, or {x, y, z}");
    }

    /** The axes {@code x}, {@code y} and {@code z} read directly from a mapping the caller already opened. */
    public static Triple readAxes(ParamReader section, String key, double min, double max, boolean whole) {
        return readAxes(section, key, min, max, whole, Set.of());
    }

    /** As above, for a mapping that also holds keys of the caller's own ({@code ignored}). */
    public static Triple readAxes(ParamReader section, String key, double min, double max, boolean whole,
            Set<String> ignored) {
        for (String name : section.keys()) {
            if (!AXES.contains(name) && !ignored.contains(name)) {
                throw new IllegalArgumentException(key + " has no axis '" + name + "' (use x, y, z)");
            }
        }
        return new Triple(axis(section, "x", min, max, whole), axis(section, "y", min, max, whole),
                axis(section, "z", min, max, whole));
    }

    private static Quantity axis(ParamReader section, String name, double min, double max, boolean whole) {
        Optional<Quantity> quantity = whole ? Quantity.optionalCount(section, name, (int) min, (int) max)
                : Quantity.optional(section, name, min, max);
        return quantity.orElseGet(() -> new Quantity(0, null, min, max, whole));
    }

    private static Quantity literal(String key, Number number, double min, double max, boolean whole) {
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < min || value > max || (whole && value != Math.rint(value))) {
            throw new IllegalArgumentException(key + " must be " + (whole ? "whole numbers in " : "numbers in ")
                    + (whole ? (long) min + ".." + (long) max : min + ".." + max));
        }
        return new Quantity(value, null, min, max, whole);
    }

    /** This triple with each template rendered once, before the step touches the world. */
    public CompletionStage<Triple> resolve(LogicContext context, ReadExecutor reads) {
        return Quantities.resolve(context, reads, x, y, z).thenApply(q -> new Triple(q[0], q[1], q[2]));
    }

    /** The three numbers, or empty when a template did not render to a number in range. */
    public Optional<Values> values(LogicContext context) {
        var vx = x.value(context);
        var vy = y.value(context);
        var vz = z.value(context);
        return vx.isEmpty() || vy.isEmpty() || vz.isEmpty() ? Optional.empty()
                : Optional.of(new Values(vx.getAsDouble(), vy.getAsDouble(), vz.getAsDouble()));
    }
}
