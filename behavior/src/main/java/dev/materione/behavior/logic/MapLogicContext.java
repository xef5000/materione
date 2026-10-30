package dev.materione.behavior.logic;

import dev.materione.api.logic.ContextFacet;
import dev.materione.api.logic.LogicContext;
import dev.materione.behavior.expr.EvaluationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The engine's {@link LogicContext}: a nested-map variable tree (the expression-visible
 * {@code player.*}, {@code item.*}, {@code vars.*}), a set of typed facets and an optional
 * placeholder resolver. The platform fills one per fired event; the same class, filled with plain
 * Java values, is the fake context every engine and action test uses.
 *
 * <p>One instance belongs to one run and is not thread-safe (see {@link LogicContext}). The root
 * map is copied on construction, so the caller keeps no handle on the live tree.
 */
public final class MapLogicContext implements LogicContext {

    private static final String LOCALS_KEY = "vars";

    private final Map<String, Object> root;
    private final EvaluationContext evaluation;
    private final Map<ContextFacet<?>, Object> facets = new LinkedHashMap<>();
    private UnaryOperator<String> placeholders = UnaryOperator.identity();

    /** A context over {@code root}: {@code player}, {@code item}, … each a nested map. */
    public MapLogicContext(Map<String, Object> root) {
        this.root = new LinkedHashMap<>(root);
        this.evaluation = new EvaluationContext(this.root).withLogic(this);
    }

    /** Adds (or replaces) the value held for {@code facet}; a null value removes it. */
    public <T> MapLogicContext with(ContextFacet<T> facet, T value) {
        if (value == null) {
            facets.remove(facet);
        } else {
            facets.put(facet, facet.cast(value));
        }
        return this;
    }

    /** Sets how {@code %token%}s are resolved; the default leaves text untouched. */
    public MapLogicContext withPlaceholders(UnaryOperator<String> resolver) {
        this.placeholders = resolver == null ? UnaryOperator.identity() : resolver;
        return this;
    }

    /** The same variable tree as the expression engine's evaluation context (rule conditions read it). */
    public EvaluationContext evaluation() {
        return evaluation;
    }

    @Override
    public <T> Optional<T> facet(ContextFacet<T> facet) {
        return Optional.ofNullable(facets.get(facet)).map(facet::cast);
    }

    @Override
    public Object resolve(List<String> path) {
        return evaluation.resolve(path);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> locals() {
        Object existing = root.get(LOCALS_KEY);
        if (existing instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        var created = new LinkedHashMap<String, Object>();
        root.put(LOCALS_KEY, created);
        return created;
    }

    @Override
    public String applyPlaceholders(String text) {
        return placeholders.apply(text);
    }
}
