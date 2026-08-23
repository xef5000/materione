package dev.materione.core.runtime;

import dev.materione.api.NamespacedId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The block half of one apply's registry set: id → definition, plus the
 * state-as-identity fast path — per-host arrays indexed by the decoded slot
 * int, so an event handler answers "is this vanilla?" with one array read
 * and zero PDC accesses (runtime-systems §4.2). Also carries the item →
 * block placement links ({@code item:} on a block definition) and swaps
 * atomically with the rest of the shadow via {@link RuntimeRegistries}.
 */
public final class RuntimeBlocks {

    private final Map<NamespacedId, RuntimeBlock> byId;
    private final Map<String, RuntimeBlock[]> slotsByHost;
    private final Map<NamespacedId, NamespacedId> placedByItem;
    private final Map<NamespacedId, NamespacedId> itemForBlock;

    private RuntimeBlocks(
            Map<NamespacedId, RuntimeBlock> byId,
            Map<String, RuntimeBlock[]> slotsByHost,
            Map<NamespacedId, NamespacedId> placedByItem,
            Map<NamespacedId, NamespacedId> itemForBlock) {
        this.byId = byId;
        this.slotsByHost = slotsByHost;
        this.placedByItem = placedByItem;
        this.itemForBlock = itemForBlock;
    }

    /** The view key under {@link RuntimeRegistries#view(Class)}. */
    public static Class<RuntimeBlocks> KEY = RuntimeBlocks.class;

    public static Builder builder() {
        return new Builder();
    }

    public Optional<RuntimeBlock> byId(NamespacedId id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Every defined block id (diff/report surface). */
    public java.util.Set<NamespacedId> ids() {
        return java.util.Set.copyOf(byId.keySet());
    }

    /**
     * The custom block whose allocated state slot {@code slot} of host
     * {@code host} (lowercase vanilla id) decodes to — empty for every
     * vanilla-occupied state (the hot path's only decision).
     */
    public Optional<RuntimeBlock> bySlot(String host, int slot) {
        RuntimeBlock[] slots = slotsByHost.get(host);
        if (slots == null || slot < 0 || slot >= slots.length) {
            return Optional.empty();
        }
        return Optional.ofNullable(slots[slot]);
    }

    /** The block a held item places, if its definition declared {@code item:}. */
    public Optional<NamespacedId> placedBy(NamespacedId itemId) {
        return Optional.ofNullable(placedByItem.get(itemId));
    }

    /** The item that places {@code blockId} (pick-block surface), if declared. */
    public Optional<NamespacedId> itemFor(NamespacedId blockId) {
        return Optional.ofNullable(itemForBlock.get(blockId));
    }

    /** All definitions (technical-host discovery, sweeps). */
    public Collection<RuntimeBlock> all() {
        return byId.values();
    }

    public int count() {
        return byId.size();
    }

    public static final class Builder {
        private final Map<NamespacedId, RuntimeBlock> byId = new LinkedHashMap<>();
        private final Map<String, LinkedHashMap<Integer, RuntimeBlock>> pending = new LinkedHashMap<>();
        private final Map<NamespacedId, NamespacedId> placedByItem = new LinkedHashMap<>();

        /** Registers a block; state-allocating techniques also index their slot. */
        public Builder block(RuntimeBlock block) {
            byId.put(block.id(), block);
            if (block.allocatesState()) {
                pending.computeIfAbsent(block.host(), host -> new LinkedHashMap<>())
                        .put(block.slot(), block);
            }
            return this;
        }

        /** Links {@code itemId} to the block it places. */
        public Builder placedBy(NamespacedId itemId, NamespacedId blockId) {
            placedByItem.put(itemId, blockId);
            return this;
        }

        public RuntimeBlocks build() {
            var slots = new LinkedHashMap<String, RuntimeBlock[]>();
            for (var entry : pending.entrySet()) {
                int max = 0;
                for (int slot : entry.getValue().keySet()) {
                    max = Math.max(max, slot);
                }
                RuntimeBlock[] array = new RuntimeBlock[max + 1];
                for (var slotEntry : entry.getValue().entrySet()) {
                    array[slotEntry.getKey()] = slotEntry.getValue();
                }
                slots.put(entry.getKey(), array);
            }
            var reverse = new LinkedHashMap<NamespacedId, NamespacedId>();
            for (var entry : placedByItem.entrySet()) {
                reverse.put(entry.getValue(), entry.getKey());
            }
            return new RuntimeBlocks(
                    Map.copyOf(byId), Map.copyOf(slots), Map.copyOf(placedByItem), Map.copyOf(reverse));
        }
    }
}
