package dev.materione.plugin.runtime.mechanics;

import dev.materione.api.NamespacedId;
import dev.materione.api.mechanics.MechanicInstance;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The resolved mechanics of item definitions in the applied shadow, keyed
 * by owning item. Same atomic-swap contract as {@link dev.materione.plugin.runtime.behavior.ItemBehaviors}:
 * listeners resolve the view at event entry, so an in-flight event always
 * completes against the snapshot it started with.
 */
public final class ItemMechanics {

    /** The registry-view key. */
    public static final Class<ItemMechanics> KEY = ItemMechanics.class;

    private final Map<NamespacedId, List<ActiveMechanic>> byItem;

    public ItemMechanics(Map<NamespacedId, List<ActiveMechanic>> byItem) {
        var copy = new java.util.LinkedHashMap<NamespacedId, List<ActiveMechanic>>();
        byItem.forEach((id, mechanics) -> copy.put(id, List.copyOf(mechanics)));
        this.byItem = Map.copyOf(copy);
    }

    public static ItemMechanics empty() {
        return new ItemMechanics(Map.of());
    }

    /** All active mechanics of one item; empty when it declares none. */
    public List<ActiveMechanic> forItem(NamespacedId id) {
        return byItem.getOrDefault(id, List.of());
    }

    /** Typed lookup scoped by full mechanic identity, never by a short name. */
    public <T extends MechanicInstance> Optional<T> onItem(
            NamespacedId id, NamespacedId mechanicId, Class<T> type) {
        for (ActiveMechanic active : forItem(id)) {
            if (active.id().equals(mechanicId) && active.isAvailable() && type.isInstance(active.instance())) {
                return Optional.of(type.cast(active.instance()));
            }
        }
        return Optional.empty();
    }
}
