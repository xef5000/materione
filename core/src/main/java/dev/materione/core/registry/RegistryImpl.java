package dev.materione.core.registry;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryEntry;
import dev.materione.api.registry.RegistryException;
import dev.materione.api.registry.RegistryKey;
import dev.materione.api.registry.SelfDescription;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry implementation. Freezing/reopening is owned by
 * {@link RegistryService}, including bounded writes for managed extensions.
 * Registration order is preserved (stable for docs and the editor).
 */
final class RegistryImpl<T> implements Registry<T> {

    private final RegistryKey key;
    private final Class<T> entryType;
    private final Map<NamespacedId, RegistryEntry<T>> entries = new LinkedHashMap<>();
    private volatile boolean frozen;
    private long nextGeneration = 1;

    RegistryImpl(RegistryKey key, Class<T> entryType) {
        this.key = key;
        this.entryType = entryType;
    }

    @Override
    public RegistryKey key() {
        return key;
    }

    @Override
    public Class<T> entryType() {
        return entryType;
    }

    void freeze() {
        frozen = true;
    }

    void reopen() {
        frozen = false;
    }

    @Override
    public void register(NamespacedId id, T entry, SelfDescription description, String registeredBy) {
        synchronized (entries) {
            if (frozen) {
                throw new RegistryException(
                        "registry '" + key + "' is frozen: registration window closed"
                                + " (register during bootstrap or a reload's re-check)");
            }
            registerBounded(id, entry, description, registeredBy);
        }
    }

    /** One service-authorized write, without reopening this or any other registry. */
    void registerBounded(NamespacedId id, T entry, SelfDescription description, String registeredBy) {
        if (id.isReservedNamespace()) {
            throw new RegistryException(
                    "namespace 'minecraft' is reserved and cannot be registered in '" + key + "'");
        }
        synchronized (entries) {
            RegistryEntry<T> existing = entries.get(id);
            if (existing != null) {
                throw new RegistryException(
                        "duplicate '" + id + "' in registry '" + key + "'"
                                + " (already registered by " + existing.registeredBy() + ")");
            }
            entries.put(id, new RegistryEntry<>(id, entry, description, registeredBy, nextGeneration++));
        }
    }

    boolean unregisterBounded(NamespacedId id, T expected) {
        synchronized (entries) {
            RegistryEntry<T> existing = entries.get(id);
            if (existing == null || existing.entry() != expected) {
                return false;
            }
            entries.remove(id);
            return true;
        }
    }

    @Override
    public Optional<RegistryEntry<T>> get(NamespacedId id) {
        synchronized (entries) {
            return Optional.ofNullable(entries.get(id));
        }
    }

    @Override
    public Collection<RegistryEntry<T>> entries() {
        synchronized (entries) {
            return List.copyOf(entries.values());
        }
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    @Override
    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }
}
