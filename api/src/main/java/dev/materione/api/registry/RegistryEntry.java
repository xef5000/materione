package dev.materione.api.registry;

import dev.materione.api.NamespacedId;
import java.util.Objects;

/**
 * One registered entry: the id, the entry itself, its self-description, and
 * who registered it (audit; shown in {@code /materione doctor}).
 */
public record RegistryEntry<T>(NamespacedId id, T entry, SelfDescription description, String registeredBy) {

    public RegistryEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(description, "description");
        registeredBy = registeredBy == null ? "unknown" : registeredBy;
    }
}
