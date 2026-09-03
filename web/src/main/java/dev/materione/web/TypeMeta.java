package dev.materione.web;

import java.util.List;

/** One content type's self-description, transport-ready (plugin.md §3). */
public record TypeMeta(
        String name,
        String directory,
        String displayName,
        String description,
        List<Param> parameters) {

    public TypeMeta {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** One declared parameter of a type's registry schema. */
    public record Param(
            String id,
            String type,
            boolean required,
            String defaultValue,
            String description,
            List<String> allowedValues,
            List<Param> nested,
            String uiHint,
            String label,
            String section,
            int order,
            boolean advanced,
            String referenceKind,
            String example) {

        public Param {
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
            nested = nested == null ? List.of() : List.copyOf(nested);
            label = label == null ? "" : label;
            section = section == null ? "general" : section;
            referenceKind = referenceKind == null ? "" : referenceKind;
            example = example == null ? "" : example;
        }
    }
}