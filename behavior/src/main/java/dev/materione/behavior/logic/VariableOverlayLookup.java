package dev.materione.behavior.logic;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.Recipe;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.behavior.expr.FunctionResolver;
import dev.materione.behavior.rule.EventSelector;
import java.util.Optional;
import java.util.function.Function;

/**
 * A lookup for one check: persistent variables resolve through the draft's own view first (the pack as a
 * transaction leaves it), then through the server's; everything else is the server's registries.
 */
public final class VariableOverlayLookup implements LogicLookup {

    private final LogicLookup base;
    private final Function<NamespacedId, Optional<VariableDeclaration>> variables;

    public VariableOverlayLookup(LogicLookup base, Function<NamespacedId, Optional<VariableDeclaration>> variables) {
        this.base = base;
        this.variables = variables;
    }

    @Override
    public Optional<VariableDeclaration> variable(NamespacedId id) {
        Optional<VariableDeclaration> declared = variables.apply(id);
        return declared.isPresent() ? declared : base.variable(id);
    }

    @Override
    public Optional<ActionType<?>> action(String authoredId) {
        return base.action(authoredId);
    }

    @Override
    public Optional<ConditionType<?>> condition(String authoredId) {
        return base.condition(authoredId);
    }

    @Override
    public Optional<dev.materione.api.logic.ItemSource<?>> itemSource(String authoredId) {
        return base.itemSource(authoredId);
    }

    @Override
    public Optional<dev.materione.api.logic.ListSource<?>> listSource(String authoredId) {
        return base.listSource(authoredId);
    }

    @Override
    public Optional<Recipe> recipe(String authoredId) {
        return base.recipe(authoredId);
    }

    @Override
    public boolean triggerKnown(String authoredId) {
        return base.triggerKnown(authoredId);
    }

    @Override
    public Optional<TriggerDescription> trigger(String authoredId) {
        return base.trigger(authoredId);
    }

    @Override
    public FunctionResolver functions() {
        return base.functions();
    }

    @Override
    public Optional<EventSelector> selector(String text) {
        return base.selector(text);
    }
}
