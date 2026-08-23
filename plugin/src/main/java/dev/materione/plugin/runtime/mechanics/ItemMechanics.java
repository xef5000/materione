package dev.materione.plugin.runtime.mechanics;

import dev.materione.api.NamespacedId;
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
        this.byItem = Map.copyOf(byItem);
    }

    public static ItemMechanics empty() {
        return new ItemMechanics(Map.of());
    }

    /** All active mechanics of one item; empty when it declares none. */
    public List<ActiveMechanic> forItem(NamespacedId id) {
        return byItem.getOrDefault(id, List.of());
    }

    /** The first mechanic of the given name on an item, if declared. */
    public Optional<ActiveMechanic> onItem(NamespacedId id, String mechanicName) {
        for (ActiveMechanic active : forItem(id)) {
            if (active.name().equals(mechanicName)) {
                return Optional.of(active);
            }
        }
        return Optional.empty();
    }
}
