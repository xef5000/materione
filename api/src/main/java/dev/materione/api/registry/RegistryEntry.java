package dev.materione.api.registry;

import dev.materione.api.NamespacedId;
import java.util.Objects;

/**
 * One registered entry: the id, the entry itself, its self-description, and
 * who registered it (audit; shown in {@code /materione doctor}).
 * Generation is monotonic within its registry and changes on replacement,
 * not on content reload; zero denotes an external legacy registry entry.
 */
public record RegistryEntry<T>(
        NamespacedId id, T entry, SelfDescription description, String registeredBy, long generation) {

    /** Compatibility constructor for external registry implementations. */
    public RegistryEntry(NamespacedId id, T entry, SelfDescription description, String registeredBy) {
        this(id, entry, description, registeredBy, 0);
    }

    public RegistryEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(description, "description");
        registeredBy = registeredBy == null ? "unknown" : registeredBy;
    }
}
