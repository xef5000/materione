package dev.materione.behavior.logic;

import dev.materione.api.logic.ContextFacet;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.behavior.expr.EvaluationContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A context that adds one root variable over another context, without copying the base's tree. A GUI
 * list uses it to read one entry: the window's context is built once per render, and each entry is an
 * overlay that answers {@code entry.<field>} from the entry's map and every other path from the base
 * (logic.md §6.7). Facets, run-local variables, persistent variables and placeholders are the base's.
 *
 * <p>One overlay belongs to one evaluation and is not thread-safe, like the context it wraps.
 */
public final class OverlayLogicContext implements LogicContext, EvaluationAware {

    private final LogicContext base;
    private final String root;
    private final Map<String, Object> value;
    private final EvaluationContext evaluation;

    /**
     * @param base the context every other path reads from
     * @param root the new root's name ({@code entry})
     * @param value the root's variables, read by {@code walk}: a nested map of plain values
     */
    public OverlayLogicContext(LogicContext base, String root, Map<String, Object> value) {
        this.base = Objects.requireNonNull(base, "base");
        this.root = Objects.requireNonNull(root, "root");
        this.value = Objects.requireNonNull(value, "value");
        this.evaluation = EvaluationContext.of(this::resolve).withLogic(this);
    }

    @Override
    public EvaluationContext evaluation() {
        return evaluation;
    }

    @Override
    public <T> Optional<T> facet(ContextFacet<T> facet) {
        return base.facet(facet);
    }

    @Override
    public Object resolve(List<String> path) {
        if (!path.isEmpty() && root.equals(path.get(0))) {
            Object current = value;
            for (String segment : path.subList(1, path.size())) {
                if (!(current instanceof Map<?, ?> map)) {
                    return null;
                }
                current = map.get(segment);
            }
            return current;
        }
        return base.resolve(path);
    }

    @Override
    public Map<String, Object> locals() {
        return base.locals();
    }

    @Override
    public VariableBinding variables() {
        return base.variables();
    }

    @Override
    public String applyPlaceholders(String text) {
        return base.applyPlaceholders(text);
    }
}
