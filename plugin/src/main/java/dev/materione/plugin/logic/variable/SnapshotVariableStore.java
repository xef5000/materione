package dev.materione.plugin.logic.variable;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Publishes immutable, last-acknowledged player values for synchronous external consumers. */
final class SnapshotVariableStore implements VariableStore {
    private record Entry(VariableDeclaration declaration, Optional<Object> value) {}

    private final VariableStore delegate;
    private final ConcurrentHashMap<UUID, Map<NamespacedId, Entry>> snapshots = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    SnapshotVariableStore(VariableStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public VariableScope scope() {
        return delegate.scope();
    }

    @Override
    public Optional<Object> read(VariableDeclaration declaration, UUID player) {
        long before = generation.get();
        PdcVariableStore.PlayerRead pdcRead = delegate instanceof PdcVariableStore pdc
                ? pdc.readPlayer(declaration, player) : null;
        Optional<Object> value = pdcRead == null ? delegate.read(declaration, player) : pdcRead.value();
        if (player != null && (pdcRead == null || pdcRead.online())) {
            snapshots.compute(player, (ignored, previous) -> {
                if (before != generation.get()) {
                    return previous;
                }
                var next = new HashMap<>(previous == null ? Map.<NamespacedId, Entry>of() : previous);
                next.put(declaration.id(), new Entry(declaration, value));
                return Map.copyOf(next);
            });
        }
        return value;
    }

    @Override
    public boolean write(VariableDeclaration declaration, UUID player, Object value) {
        invalidate(player);
        boolean written = delegate.write(declaration, player, value);
        invalidate(player);
        if (written) {
            try {
                read(declaration, player);
            } catch (ReadUnavailableException retired) {
                // The write succeeded but the holder retired before a snapshot could be read.
            }
        }
        return written;
    }

    /** Empty outer optional means no acknowledged snapshot; inner empty means an unset value. */
    Optional<Optional<Object>> snapshot(VariableDeclaration declaration, UUID player) {
        if (player == null) {
            return Optional.empty();
        }
        Entry entry = snapshots.getOrDefault(player, Map.of()).get(declaration.id());
        return entry != null && entry.declaration().equals(declaration)
                ? Optional.of(entry.value()) : Optional.empty();
    }

    void invalidate(UUID player) {
        generation.incrementAndGet();
        if (player != null) {
            snapshots.remove(player);
        }
    }

    void invalidateAll() {
        generation.incrementAndGet();
        snapshots.clear();
    }
}
