package dev.materione.behavior.logic;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.behavior.expr.FunctionResolver;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryKey;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.RegistryView;
import java.util.Optional;
import java.util.function.Function;

/**
 * The {@link LogicLookup} over the live registries ({@code actions}, {@code conditions},
 * {@code triggers}). It reads the registries at each call rather than caching them, so a late
 * addon registration is visible to the next rule load.
 */
public final class RegistryLogicLookup implements LogicLookup {

    private final RegistryView registries;
    private final Function<NamespacedId, Optional<VariableDeclaration>> declarations;

    public RegistryLogicLookup(RegistryView registries) {
        this(registries, id -> Optional.empty());
    }

    /**
     * @param declarations the persistent variables the content being compiled declares; the plugin
     *        passes the ones of the shadow under construction
     */
    public RegistryLogicLookup(
            RegistryView registries, Function<NamespacedId, Optional<VariableDeclaration>> declarations) {
        this.registries = registries;
        this.declarations = declarations;
    }

    @Override
    public Optional<VariableDeclaration> variable(NamespacedId id) {
        return declarations.apply(id);
    }

    @Override
    public Optional<ActionType<?>> action(String authoredId) {
        return find(RegistryKeys.ACTIONS, authoredId, ActionType.class).map(type -> (ActionType<?>) type);
    }

    @Override
    public Optional<ConditionType<?>> condition(String authoredId) {
        return find(RegistryKeys.CONDITIONS, authoredId, ConditionType.class).map(type -> (ConditionType<?>) type);
    }

    @Override
    public boolean triggerKnown(String authoredId) {
        return find(RegistryKeys.TRIGGERS, authoredId, TriggerType.class).isPresent();
    }

    @Override
    public Optional<TriggerDescription> trigger(String authoredId) {
        return find(RegistryKeys.TRIGGERS, authoredId, TriggerType.class).flatMap(trigger -> {
            try {
                return Optional.ofNullable(trigger.describe());
            } catch (RuntimeException e) {
                return Optional.empty(); // a broken description is "unknown", never a failed load
            }
        });
    }

    /** Functions resolve against the live {@code expression-functions} registry, read at each rule load. */
    @Override
    public FunctionResolver functions() {
        return name -> find(RegistryKeys.EXPRESSION_FUNCTIONS, name, ExpressionFunction.class);
    }

    private <T> Optional<T> find(RegistryKey key, String authoredId, Class<T> type) {
        Optional<NamespacedId> id = LogicIds.parse(authoredId);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return registries.byKey(key)
                .flatMap(registry -> entryOf(registry, id.get()))
                .filter(type::isInstance)
                .map(type::cast);
    }

    private static Optional<Object> entryOf(Registry<?> registry, NamespacedId id) {
        return registry.get(id).map(entry -> (Object) entry.entry());
    }
}
