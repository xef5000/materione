package dev.materione.behavior.run;

import dev.materione.api.logic.ContextFacet;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ResolvedTarget;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.behavior.expr.EvaluationContext;
import dev.materione.behavior.logic.EvaluationAware;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The run's context for one targeted action (logic.md §39): the run's own context with one more facet, the
 * {@link ResolvedTarget#FACET target}. Nothing else changes, which is the point: an expression, a placeholder or a
 * run-local variable the action reads is the run's own (a message to the other player still says whose name the
 * sender has), and only the action's effect, read through {@code Subjects}, moves to the target.
 *
 * <p>One context belongs to one action on one target and, like the context it wraps, is not thread-safe.
 */
final class TargetedContext implements LogicContext, EvaluationAware {

    private final LogicContext base;
    private final ResolvedTarget target;
    private final EvaluationContext evaluation;

    TargetedContext(LogicContext base, ResolvedTarget target) {
        this.base = Objects.requireNonNull(base, "base");
        this.target = Objects.requireNonNull(target, "target");
        this.evaluation = EvaluationContext.of(this::resolve).withLogic(this);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> facet(ContextFacet<T> facet) {
        if (ResolvedTarget.FACET.equals(facet)) {
            return Optional.of((T) target);
        }
        return base.facet(facet);
    }

    @Override
    public Object resolve(List<String> path) {
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

    @Override
    public EvaluationContext evaluation() {
        return evaluation;
    }
}
