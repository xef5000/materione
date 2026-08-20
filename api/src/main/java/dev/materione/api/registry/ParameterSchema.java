package dev.materione.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * One declared parameter of a registry entry. Part of the entry's
 * self-description: this is the single source of truth that generates editor
 * forms, docs, and tab completion (plugin architecture §3 — hand-written
 * per-entry editor forms are forbidden because they drift).
 *
 * @param defaultValue nullable; absent means "no default"
 * @param allowedValues only meaningful for {@link ParameterType#ENUM}
 * @param nested only meaningful for {@link ParameterType#LIST}
 */
public record ParameterSchema(
        String id,
        ParameterType type,
        boolean required,
        String defaultValue,
        String description,
        List<String> allowedValues,
        List<ParameterSchema> nested) {

    public ParameterSchema {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        nested = nested == null ? List.of() : List.copyOf(nested);
    }

    /** Simple scalar parameter without enum values or nesting. */
    public static ParameterSchema of(String id, ParameterType type, boolean required) {
        return new ParameterSchema(id, type, required, null, null, List.of(), List.of());
    }
}
