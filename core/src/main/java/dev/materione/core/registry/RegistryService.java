package dev.materione.core.registry;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryKey;
import dev.materione.api.registry.RegistryView;
import dev.materione.api.registry.SelfDescription;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns every registry and the global registration window (D5). Registries
 * freeze when content loads; a reload re-opens the window, re-checks,
 * re-freezes. Creating a registry with a duplicate key is a developer error
 * and throws.
 */
public final class RegistryService implements RegistryView {

    private final Map<RegistryKey, RegistryImpl<?>> registries = new LinkedHashMap<>();

    /** Creates (or returns) the registry for a key. */
    public synchronized <T> Registry<T> registry(RegistryKey key, Class<T> entryType) {
        RegistryImpl<?> existing = registries.get(key);
        if (existing != null) {
            if (existing.entryType() != entryType) {
                throw new IllegalStateException(
                        "registry '" + key + "' exists with entry type "
                                + existing.entryType().getName() + ", not " + entryType.getName());
            }
            return cast(existing);
        }
        RegistryImpl<T> created = new RegistryImpl<>(key, entryType);
        registries.put(key, created);
        return created;
    }

    /** Typed lookup used by internal services. */
    @SuppressWarnings("unchecked")
    public synchronized <T> Optional<Registry<T>> byKey(RegistryKey key, Class<T> entryType) {
        RegistryImpl<?> found = registries.get(key);
        return found == null || found.entryType() != entryType
                ? Optional.empty()
                : Optional.of((Registry<T>) found);
    }

    @Override
    public synchronized Optional<Registry<?>> byKey(RegistryKey key) {
        return Optional.ofNullable(registries.get(key)).map(r -> r);
    }

    @Override
    public synchronized Collection<RegistryKey> keys() {
        return List.copyOf(registries.keySet());
    }

    /** Closes the registration window in every registry. */
    public synchronized void freezeAll() {
        registries.values().forEach(RegistryImpl::freeze);
    }

    /** Re-opens the registration window (reload-time only). */
    public synchronized void reopenAllWindows() {
        registries.values().forEach(RegistryImpl::reopen);
    }

    /**
     * Authorizes exactly one managed entry registration. Normal registration
     * windows, including all unrelated registries' frozen state, are unchanged.
     */
    public synchronized <T> void registerBounded(
            RegistryKey key, Class<T> entryType, NamespacedId id, T entry,
            SelfDescription description, String registeredBy) {
        registry(key, entryType);
        RegistryImpl<T> registry = castImpl(registries.get(key));
        registry.registerBounded(id, entry, description, registeredBy);
    }

    /** Removes only the same managed entry, never a later registration of its id. */
    public synchronized <T> boolean unregisterBounded(
            RegistryKey key, Class<T> entryType, NamespacedId id, T expected) {
        RegistryImpl<?> found = registries.get(key);
        if (found == null || found.entryType() != entryType) {
            return false;
        }
        RegistryImpl<T> registry = castImpl(found);
        return registry.unregisterBounded(id, expected);
    }

    public synchronized int registryCount() {
        return registries.size();
    }

    public synchronized int totalEntries() {
        return registries.values().stream().mapToInt(RegistryImpl::size).sum();
    }

    /** Summary lines for {@code /materione doctor}. */
    public synchronized List<String> doctorLines() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<RegistryKey, RegistryImpl<?>> entry : registries.entrySet()) {
            RegistryImpl<?> registry = entry.getValue();
            lines.add("registry " + entry.getKey() + ": " + registry.size()
                    + " entries, " + (registry.isFrozen() ? "frozen" : "open"));
        }
        return lines;
    }

    @SuppressWarnings("unchecked")
    private static <T> Registry<T> cast(RegistryImpl<?> registry) {
        return (Registry<T>) registry;
    }

    @SuppressWarnings("unchecked")
    private static <T> RegistryImpl<T> castImpl(RegistryImpl<?> registry) {
        return (RegistryImpl<T>) registry;
    }
}
