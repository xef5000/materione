package dev.materione.api.registry;

import java.util.Collection;
import java.util.Optional;

/** Read-only view over all registries (exposed via {@code MaterioneAPI}). */
public interface RegistryView {

    Optional<Registry<?>> byKey(RegistryKey key);

    Collection<RegistryKey> keys();
}
