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
 * @param editor declarative presentation metadata; it never weakens validation
 */
public record ParameterSchema(
        String id,
        ParameterType type,
        boolean required,
        String defaultValue,
        String description,
        List<String> allowedValues,
        List<ParameterSchema> nested,
        EditorMetadata editor) {

    public ParameterSchema {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        nested = nested == null ? List.of() : List.copyOf(nested);
        editor = editor == null ? EditorMetadata.defaults(id, type) : editor;
    }

    /** Compatibility constructor for existing content types and addons. */
    public ParameterSchema(String id, ParameterType type, boolean required, String defaultValue,
            String description, List<String> allowedValues, List<ParameterSchema> nested) {
        this(id, type, required, defaultValue, description, allowedValues, nested,
                EditorMetadata.defaults(id, type));
    }

    /** Simple scalar parameter without enum values or nesting. */
    public static ParameterSchema of(String id, ParameterType type, boolean required) {
        return new ParameterSchema(id, type, required, null, null, List.of(), List.of());
    }

    /** Explicit presentation metadata for a field while retaining one schema source. */
    public ParameterSchema withEditor(EditorMetadata metadata) {
        return new ParameterSchema(id, type, required, defaultValue, description,
                allowedValues, nested, metadata);
    }

    /**
     * Compatibility accessor for older editor clients. New clients must use
     * {@link #editor()} so widgets are explicit rather than inferred in the SPA.
     */
    public String uiHint() {
        return editor.widget();
    }

    /**
     * Declarative, additive rendering metadata. Validation remains in the
     * content type; this object only controls progressive disclosure.
     */
    public record EditorMetadata(
            String label,
            String section,
            int order,
            boolean advanced,
            String widget,
            String referenceKind,
            String example) {

        public EditorMetadata {
            label = label == null || label.isBlank() ? "" : label;
            section = section == null || section.isBlank() ? "general" : section;
            widget = widget == null || widget.isBlank() ? "text" : widget;
            referenceKind = referenceKind == null ? "" : referenceKind;
            example = example == null ? "" : example;
        }

        public static EditorMetadata defaults(String id, ParameterType type) {
            String widget = switch (type) {
                case BOOLEAN -> "toggle";
                case INTEGER, DECIMAL -> "number";
                case ENUM -> "select";
                case LIST -> "list";
                case RAW -> "advanced";
                case REFERENCE -> id.endsWith("texture") ? "texture" : id.equals("model") ? "model" : "reference";
                default -> "text";
            };
            String section = id.equals("model") || id.endsWith("texture") || id.equals("model_parent")
                    ? "appearance" : id.equals("mechanics") || id.equals("behavior") ? "behavior" : "general";
            return new EditorMetadata(humanize(id), section, 0, type == ParameterType.RAW,
                    widget, "", "");
        }

        private static String humanize(String value) {
            String[] words = value.replace('_', ' ').split(" ");
            StringBuilder out = new StringBuilder();
            for (String word : words) {
                if (word.isEmpty()) continue;
                if (!out.isEmpty()) out.append(' ');
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return out.toString();
        }
    }
}
