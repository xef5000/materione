package dev.materione.behavior.logic;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ContextFacet;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableFacets;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.behavior.expr.EvaluationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The engine's {@link LogicContext}: a nested-map variable tree (the expression-visible
 * {@code player.*}, {@code item.*}, {@code vars.*}), a set of typed facets, the run's persistent
 * variables and an optional placeholder resolver. The platform fills one per fired event; the same
 * class, filled with plain Java values, is the fake context every engine and action test uses.
 *
 * <p>Reading {@code vars} follows the scopes of logic.md §3.3: a bare {@code vars.x} and
 * {@code vars.local.x} are the run's locals, {@code vars.session.x} is the GUI session's variable,
 * and {@code vars.player.<pack>.<key>} / {@code vars.server.<pack>.<key>} are declared persistent
 * variables. A declared variable that was never written reads as its default; an undeclared one
 * reads as absent.
 *
 * <p>One instance belongs to one run and is not thread-safe (see {@link LogicContext}). The root
 * map is copied on construction, so the caller keeps no handle on the live tree.
 */
public final class MapLogicContext implements LogicContext, EvaluationAware {

    private static final String LOCALS_KEY = "vars";

    private final Map<String, Object> root;
    private final EvaluationContext evaluation;
    private final Map<ContextFacet<?>, Object> facets = new LinkedHashMap<>();
    private UnaryOperator<String> placeholders = UnaryOperator.identity();
    private VariableBinding variables = VariableBinding.NONE;

    /** A context over {@code root}: {@code player}, {@code item}, … each a nested map. */
    public MapLogicContext(Map<String, Object> root) {
        this.root = new LinkedHashMap<>(root);
        this.evaluation = EvaluationContext.of(this::resolveTree).withLogic(this);
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

    /** Sets the run's persistent variables; the default has none. */
    public MapLogicContext withVariables(VariableBinding binding) {
        this.variables = binding == null ? VariableBinding.NONE : binding;
        return this;
    }

    /** The same variable tree as the expression engine's evaluation context (rule conditions read it). */
    @Override
    public EvaluationContext evaluation() {
        return evaluation;
    }

    @Override
    public <T> Optional<T> facet(ContextFacet<T> facet) {
        return Optional.ofNullable(facets.get(facet)).map(facet::cast);
    }

    @Override
    public VariableBinding variables() {
        return variables;
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

    /** The variable tree: the scopes under {@code vars}, every other path the plain nested map. */
    private Object resolveTree(List<String> path) {
        if (path.size() < 2 || !LOCALS_KEY.equals(path.get(0))) {
            return walk(root, path);
        }
        String head = path.get(1);
        List<String> rest = path.subList(2, path.size());
        return switch (head) {
            case "local" -> walk(locals(), rest);
            case "session" -> session(rest);
            case "player" -> persistent(VariableScope.PLAYER, rest);
            case "server" -> persistent(VariableScope.SERVER, rest);
            default -> walk(root, path);
        };
    }

    private Object session(List<String> rest) {
        if (rest.size() != 1) {
            return null;
        }
        return facet(VariableFacets.SESSION).flatMap(session -> session.get(rest.get(0))).orElse(null);
    }

    /** {@code <pack>.<key>} under a persistent scope: the stored value, or the declared default. */
    private Object persistent(VariableScope scope, List<String> rest) {
        if (rest.size() != 2) {
            return null;
        }
        NamespacedId id;
        try {
            id = NamespacedId.of(rest.get(0), rest.get(1));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        Optional<VariableDeclaration> declaration = variables.declaration(id)
                .filter(found -> found.scope() == scope);
        if (declaration.isEmpty()) {
            return null;
        }
        return variables.stored(declaration.get()).orElse(declaration.get().defaultValue());
    }

    private static Object walk(Object start, List<String> segments) {
        Object current = start;
        for (String segment : segments) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }
}
