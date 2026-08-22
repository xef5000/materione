package dev.materione.core.runtime;

import dev.materione.api.NamespacedId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * The immutable registry set one apply produced — the "shadow" that swaps
 * atomically (plugin.md §4.5). Readers never lock; a swap publishes a new
 * instance. Rollback is re-publishing the previous instance.
 */
public final class RuntimeRegistries {

    private final Map<NamespacedId, RuntimeItem> items;

    private RuntimeRegistries(Map<NamespacedId, RuntimeItem> items) {
        this.items = items;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Empty registries (boot state before the first successful apply). */
    public static RuntimeRegistries empty() {
        return new RuntimeRegistries(Map.of());
    }

    /** Registry-map hit — the &lt;1 µs budget path (plugin.md §9). */
    public Optional<RuntimeItem> item(NamespacedId id) {
        return Optional.ofNullable(items.get(id));
    }

    public Collection<RuntimeItem> items() {
        return items.values();
    }

    public int itemCount() {
        return items.size();
    }

    public static final class Builder {
        private final Map<NamespacedId, RuntimeItem> items = new java.util.LinkedHashMap<>();

        public Builder item(RuntimeItem item) {
            items.put(item.id(), item);
            return this;
        }

        public RuntimeRegistries build() {
            return new RuntimeRegistries(Map.copyOf(items));
        }
    }
}
