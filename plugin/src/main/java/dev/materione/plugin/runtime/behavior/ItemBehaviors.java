package dev.materione.plugin.runtime.behavior;

import dev.materione.api.NamespacedId;
import dev.materione.behavior.rule.Rule;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The compiled behavior rules of the applied shadow, keyed by owning
 * resource. Registered as a {@link dev.materione.core.runtime.RuntimeRegistries}
 * view so rules swap atomically with everything else — an in-flight event
 * always resolves against one consistent snapshot.
 */
public final class ItemBehaviors {

    /** The registry-view key. */
    public static final Class<ItemBehaviors> KEY = ItemBehaviors.class;

    private final Map<NamespacedId, List<Rule>> byItem;

    public ItemBehaviors(Map<NamespacedId, List<Rule>> byItem) {
        this.byItem = Map.copyOf(byItem);
    }

    public static ItemBehaviors empty() {
        return new ItemBehaviors(Map.of());
    }

    public Optional<List<Rule>> forItem(NamespacedId id) {
        return Optional.ofNullable(byItem.get(id));
    }

    public int ruleCount() {
        return byItem.values().stream().mapToInt(List::size).sum();
    }
}
