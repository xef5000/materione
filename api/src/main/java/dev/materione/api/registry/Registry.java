package dev.materione.api.registry;

import dev.materione.api.NamespacedId;
import java.util.Collection;
import java.util.Optional;

/**
 * A typed registry of entries under namespaced ids (D5: registries all the
 * way down). Window semantics are owned by the registry service that created
 * this registry: registration succeeds only while the window is open
 * (bootstrap or a reload's re-check); once frozen, {@link #register} throws.
 */
public interface Registry<T> {

    RegistryKey key();

    Class<T> entryType();

    /**
     * Registers an entry.
     *
     * @throws RegistryException duplicate id, reserved namespace
     *         ({@code minecraft}), or frozen window
     */
    void register(NamespacedId id, T entry, SelfDescription description, String registeredBy);

    Optional<RegistryEntry<T>> get(NamespacedId id);

    Collection<RegistryEntry<T>> entries();

    boolean isFrozen();

    int size();
}
