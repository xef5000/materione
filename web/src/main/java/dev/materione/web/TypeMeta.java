package dev.materione.web;

import java.util.List;

/**
 * One content type's self-description, transport-ready (plugin.md §3): the
 * single schema source that generates editor forms, docs and tab completion.
 * The host adapts {@code api} SelfDescriptions into these records; web/ only
 * serializes them — hand-written per-type editor forms are forbidden because
 * they drift.
 */
public record TypeMeta(
        String name,
        String displayName,
        String description,
        List<Param> parameters) {

    public TypeMeta {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** One declared parameter of a type's schema. */
    public record Param(
            String id,
            String type,
            boolean required,
            String defaultValue,
            String description,
            List<String> allowedValues,
            List<Param> nested) {

        public Param {
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
            nested = nested == null ? List.of() : List.copyOf(nested);
        }
    }
}
