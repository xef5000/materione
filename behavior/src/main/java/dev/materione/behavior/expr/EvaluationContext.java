package dev.materione.behavior.expr;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The expression-visible variable space (runtime-systems §2.2): dotted paths walk nested maps, or
 * whatever resolver the host supplies (the logic context's variable tree).
 */
public final class EvaluationContext {

    private final Function<List<String>, Object> resolver;

    /** A context over a nested-map tree. */
    public EvaluationContext(Map<String, Object> root) {
        this.resolver = segments -> walk(root, segments);
    }

    private EvaluationContext(Function<List<String>, Object> resolver) {
        this.resolver = resolver;
    }

    /** A context over an arbitrary resolver (a missing path must resolve to {@code null}). */
    public static EvaluationContext of(Function<List<String>, Object> resolver) {
        return new EvaluationContext(resolver);
    }

    /**
     * Walks {@code segments} through nested maps. A missing segment or a
     * scalar mid-path resolves to {@code null} — absent variables are data,
     * not errors.
     */
    public Object resolve(List<String> segments) {
        return resolver.apply(segments);
    }

    private static Object walk(Map<String, Object> root, List<String> segments) {
        Object current = root;
        for (String segment : segments) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }
}
