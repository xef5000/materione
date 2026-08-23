package dev.materione.plugin.runtime.behavior;

import dev.materione.api.NamespacedId;
import dev.materione.behavior.rule.Rule;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compiled behavior rules of furniture definitions in the applied shadow,
 * keyed by owning furniture. Same atomic-swap contract as
 * {@link ItemBehaviors}.
 */
public final class FurnitureBehaviors {

    /** The registry-view key. */
    public static final Class<FurnitureBehaviors> KEY = FurnitureBehaviors.class;

    private final Map<NamespacedId, List<Rule>> byFurniture;

    public FurnitureBehaviors(Map<NamespacedId, List<Rule>> byFurniture) {
        this.byFurniture = Map.copyOf(byFurniture);
    }

    public static FurnitureBehaviors empty() {
        return new FurnitureBehaviors(Map.of());
    }

    public Optional<List<Rule>> forFurniture(NamespacedId id) {
        return Optional.ofNullable(byFurniture.get(id));
    }
}
