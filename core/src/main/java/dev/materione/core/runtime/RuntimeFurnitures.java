package dev.materione.core.runtime;

import dev.materione.api.NamespacedId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The furniture half of one apply's registry set: id → definition plus the
 * item ↔ furniture placement links. Swaps atomically with the rest of the
 * shadow via {@link RuntimeRegistries}; placed clusters keep their stored
 * ids across swaps (rebuild-visuals-only policy, runtime-systems §5).
 */
public final class RuntimeFurnitures {

    private final Map<NamespacedId, RuntimeFurniture> byId;
    private final Map<NamespacedId, NamespacedId> placedByItem;
    private final Map<NamespacedId, NamespacedId> itemFor;

    private RuntimeFurnitures(
            Map<NamespacedId, RuntimeFurniture> byId,
            Map<NamespacedId, NamespacedId> placedByItem,
            Map<NamespacedId, NamespacedId> itemFor) {
        this.byId = byId;
        this.placedByItem = placedByItem;
        this.itemFor = itemFor;
    }

    /** The view key under {@link RuntimeRegistries#view(Class)}. */
    public static Class<RuntimeFurnitures> KEY = RuntimeFurnitures.class;

    public static Builder builder() {
        return new Builder();
    }

    public Optional<RuntimeFurniture> byId(NamespacedId id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The furniture a held item places, if its definition declared {@code item:}. */
    public Optional<NamespacedId> placedBy(NamespacedId itemId) {
        return Optional.ofNullable(placedByItem.get(itemId));
    }

    /** The item that places {@code furnitureId} (removal drops), if declared. */
    public Optional<NamespacedId> itemFor(NamespacedId furnitureId) {
        return Optional.ofNullable(itemFor.get(furnitureId));
    }

    public int count() {
        return byId.size();
    }

    public static final class Builder {
        private final Map<NamespacedId, RuntimeFurniture> byId = new LinkedHashMap<>();
        private final Map<NamespacedId, NamespacedId> placedByItem = new LinkedHashMap<>();

        public Builder furniture(RuntimeFurniture furniture) {
            byId.put(furniture.id(), furniture);
            return this;
        }

        /** Links {@code itemId} to the furniture it places. */
        public Builder placedBy(NamespacedId itemId, NamespacedId furnitureId) {
            placedByItem.put(itemId, furnitureId);
            return this;
        }

        public RuntimeFurnitures build() {
            var reverse = new LinkedHashMap<NamespacedId, NamespacedId>();
            for (var entry : placedByItem.entrySet()) {
                reverse.put(entry.getValue(), entry.getKey());
            }
            return new RuntimeFurnitures(Map.copyOf(byId), Map.copyOf(placedByItem), Map.copyOf(reverse));
        }
    }
}
