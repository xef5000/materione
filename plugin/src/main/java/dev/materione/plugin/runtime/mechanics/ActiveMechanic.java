package dev.materione.plugin.runtime.mechanics;

import dev.materione.api.NamespacedId;
import dev.materione.api.mechanics.ItemModifier;
import dev.materione.api.mechanics.MechanicInstance;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One fully compiled declaration, ready to publish in the runtime shadow. */
public record ActiveMechanic(
        NamespacedId id,
        Map<String, Object> params,
        MechanicInstance instance,
        List<Map<String, Object>> behaviorRules,
        List<ItemModifier> itemModifiers,
        BooleanSupplier available,
        long generation) {
    public ActiveMechanic {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(available, "available");
        behaviorRules = List.copyOf(behaviorRules);
        itemModifiers = List.copyOf(itemModifiers);
    }

    public boolean isAvailable() {
        return available.getAsBoolean();
    }
}
