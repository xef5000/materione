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

    /** One labelled choice of an enum or fixed-number parameter. */
    public record EditorOption(String value, String label, String description, String icon) {

        public EditorOption {
            Objects.requireNonNull(value, "value");
            label = label == null || label.isBlank() ? value : label;
            description = description == null ? "" : description;
            icon = icon == null ? "" : icon;
        }

        public static EditorOption of(String value, String label, String description) {
            return new EditorOption(value, label, description, "");
        }
    }

        /**
         * Declarative, additive rendering metadata. Validation remains in the
         * content type; this object only controls progressive disclosure.
         *
         * <p>Module metadata (issue #152): a parameter marked {@code module} is
         * one thing a user wants — an add/remove card in a module-based studio,
         * not an always-present form row. Modules come from this
         * self-description, never from a hand-written editor list. A
         * {@code mechanics} widget marked as a module is a <em>source</em>: the
         * editor expands it into one module per registered mechanic.
         *
         * <p>Smart-input metadata (issue #153): {@code options} give an enum or
         * a fixed set of numbers human labels (the stored value never changes);
         * {@code min}/{@code max}/{@code unit} give a number field its range and
         * unit. All of it is presentation only — validation stays in the type.
         *
         * @param module this parameter renders as an add/remove module card
         * @param category picker category id ("appearance", "behaviour", …);
         *        blank falls back to {@code section}
         * @param icon icon registry name in the editor's one icon set; blank
         *        falls back to the category's icon
         * @param summary one line the module picker shows; blank falls back to
         *        the parameter description
         * @param options labelled choices (stored value + label + description);
         *        empty means the raw allowed values are the labels
         * @param min inclusive lower bound the number control enforces, or null
         * @param max inclusive upper bound the number control enforces, or null
         * @param unit unit suffix the number control shows ("blocks", "s"), blank for none
         * @param display this parameter belongs on the studio's display card (the
         *        resource's own identity: a recipe's mode and result, a painting's
         *        size) rather than being an add/remove module
         * @param shownWhen {@code field=value[|value…]}: the parameter applies only
         *        while that sibling holds one of the values (a recipe's grid shows
         *        for {@code type=shaped}); an unset sibling counts as its default.
         *        Keys that do not apply stay in the file untouched.
         */
        public record EditorMetadata(
                String label,
                String section,
                int order,
                boolean advanced,
                String widget,
                String referenceKind,
                String example,
                boolean module,
                String category,
                String icon,
                String summary,
                List<ParameterSchema.EditorOption> options,
                Double min,
                Double max,
                String unit,
                boolean display,
                String shownWhen) {

            public EditorMetadata {
                label = label == null || label.isBlank() ? "" : label;
                section = section == null || section.isBlank() ? "general" : section;
                widget = widget == null || widget.isBlank() ? "text" : widget;
                referenceKind = referenceKind == null ? "" : referenceKind;
                example = example == null ? "" : example;
                category = category == null || category.isBlank() ? "" : category;
                icon = icon == null || icon.isBlank() ? "" : icon;
                summary = summary == null || summary.isBlank() ? "" : summary;
                options = options == null ? List.of() : List.copyOf(options);
                unit = unit == null || unit.isBlank() ? "" : unit;
                shownWhen = shownWhen == null ? "" : shownWhen.trim();
            }

            /** Compatibility constructor for callers predating display/shownWhen. */
            public EditorMetadata(
                    String label, String section, int order, boolean advanced,
                    String widget, String referenceKind, String example,
                    boolean module, String category, String icon, String summary,
                    List<ParameterSchema.EditorOption> options, Double min, Double max, String unit) {
                this(label, section, order, advanced, widget, referenceKind, example,
                        module, category, icon, summary, options, min, max, unit, false, "");
            }

            /** Compatibility constructor for callers predating smart-input metadata. */
            public EditorMetadata(
                    String label, String section, int order, boolean advanced,
                    String widget, String referenceKind, String example,
                    boolean module, String category, String icon, String summary) {
                this(label, section, order, advanced, widget, referenceKind, example,
                        module, category, icon, summary, List.of(), null, null, "", false, "");
            }

            /** Compatibility constructor for callers predating module metadata. */
            public EditorMetadata(
                    String label, String section, int order, boolean advanced,
                    String widget, String referenceKind, String example) {
                this(label, section, order, advanced, widget, referenceKind, example,
                        false, "", "", "");
            }

            /** This metadata declared as a module (category/icon/summary as given). */
            public EditorMetadata asModule(String category, String icon, String summary) {
                return new EditorMetadata(label, section, order, advanced, widget, referenceKind,
                        example, true, category, icon, summary, options, min, max, unit, display, shownWhen);
            }

            /** This metadata with labelled choices. */
            public EditorMetadata withOptions(List<ParameterSchema.EditorOption> choices) {
                return new EditorMetadata(label, section, order, advanced, widget, referenceKind,
                        example, module, category, icon, summary, choices, min, max, unit, display, shownWhen);
            }

            /** This metadata with a number range and unit ({@code null} bound = open). */
            public EditorMetadata withRange(Double lower, Double upper, String suffix) {
                return new EditorMetadata(label, section, order, advanced, widget, referenceKind,
                        example, module, category, icon, summary, options, lower, upper, suffix, display, shownWhen);
            }

            /** This metadata placed on the display card instead of the module list. */
            public EditorMetadata onDisplayCard() {
                return new EditorMetadata(label, section, order, advanced, widget, referenceKind,
                        example, false, category, icon, summary, options, min, max, unit, true,
                        shownWhen);
            }

            /** This metadata that applies only while a sibling holds one of the values. */
            public EditorMetadata shownWhen(String condition) {
                return new EditorMetadata(label, section, order, advanced, widget, referenceKind,
                        example, module, category, icon, summary, options, min, max, unit, display,
                        condition);
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
