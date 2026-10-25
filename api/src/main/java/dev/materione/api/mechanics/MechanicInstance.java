package dev.materione.api.mechanics;

import java.util.List;
import java.util.Map;

/**
 * Immutable, validated parameters for one item. Implement as a typed record;
 * listeners retrieve that record through {@link MechanicLookup}, not YAML.
 * Contributions must be deterministic and side-effect free to construct.
 */
public interface MechanicInstance {

    /** Rules in the shared {@code behavior:} vocabulary, compiled by the one ECA engine. */
    default List<Map<String, Object>> behaviorRules() {
        return List.of();
    }

    /** Ordered hooks used by every stack factory and the inventory refresher. */
    default List<ItemModifier> itemModifiers() {
        return List.of();
    }
}
