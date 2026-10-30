package dev.materione.behavior.expr;

import dev.materione.api.logic.ContextFacet;
import dev.materione.api.logic.LogicContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The expression-visible variable space (runtime-systems §2.2): dotted paths walk nested maps, or
 * whatever resolver the host supplies (the logic context's variable tree).
 *
 * <p>A context may also carry the run's {@link LogicContext}, which an expression function reads
 * through ({@code is_player} reads the player facet). A context without one hands functions an
 * empty view: no facets, the same variables.
 */
public final class EvaluationContext {

    private final Function<List<String>, Object> resolver;
    private final LogicContext logic;

    /** A context over a nested-map tree. */
    public EvaluationContext(Map<String, Object> root) {
        this(segments -> walk(root, segments), null);
    }

    private EvaluationContext(Function<List<String>, Object> resolver, LogicContext logic) {
        this.resolver = resolver;
        this.logic = logic;
    }

    /** A context over an arbitrary resolver (a missing path must resolve to {@code null}). */
    public static EvaluationContext of(Function<List<String>, Object> resolver) {
        return new EvaluationContext(resolver, null);
    }

    /** This context, with the run's logic context attached for the functions that read facets. */
    public EvaluationContext withLogic(LogicContext run) {
        return new EvaluationContext(resolver, run);
    }

    /**
     * Walks {@code segments} through nested maps. A missing segment or a
     * scalar mid-path resolves to {@code null} — absent variables are data,
     * not errors.
     */
    public Object resolve(List<String> segments) {
        return resolver.apply(segments);
    }

    /** The run's logic context as a function sees it; an empty facet view when none is attached. */
    public LogicContext logic() {
        return logic != null ? logic : new VariablesOnly(this);
    }

    /** A logic context with the variables of an evaluation context and no facets, for evaluation without a run. */
    private record VariablesOnly(EvaluationContext variables) implements LogicContext {
        @Override
        public <T> Optional<T> facet(ContextFacet<T> facet) {
            return Optional.empty();
        }

        @Override
        public Object resolve(List<String> path) {
            return variables.resolve(path);
        }

        @Override
        public Map<String, Object> locals() {
            return new LinkedHashMap<>();
        }
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
