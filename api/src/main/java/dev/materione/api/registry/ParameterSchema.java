package dev.materione.api.registry;

import dev.materione.api.logic.ContextVariable;
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
 * @param semantics what the value is beyond its type (a list of steps, a condition, a value that may be an
 *        expression, a filter that takes a list): what a consumer that writes documents needs and a form does not
 */
public record ParameterSchema(
        String id,
        ParameterType type,
        boolean required,
        String defaultValue,
        String description,
        List<String> allowedValues,
        List<ParameterSchema> nested,
        EditorMetadata editor,
        Semantics semantics) {

    /** A value that is a list of steps of the flow model (logic.md section 2.2). */
    public static final String KIND_STEPS = "steps";
    /** A value that is one condition: a registered condition, a composite, or an expression. */
    public static final String KIND_CONDITION = "condition";
    /** A value that is a list of conditions. */
    public static final String KIND_CONDITIONS = "conditions";
    /** A value that names a flow of the same resource. */
    public static final String KIND_FLOW = "flow";
    /** A value that is a mapping of argument names to values. */
    public static final String KIND_ARGUMENTS = "arguments";
    /** A value that is a target: {@code self}, {@code event.target}, or a mapping with arg, player or nearby (logic.md section 39). */
    public static final String KIND_TARGET = "target";
    /** A timer interval: whole server ticks or a duration; the minimum is one second. */
    public static final String KIND_INTERVAL = "interval";
    /** A local time of day in server time, written as {@code HH:MM}. */
    public static final String KIND_TIME_OF_DAY = "time-of-day";

    public ParameterSchema {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        nested = nested == null ? List.of() : List.copyOf(nested);
        editor = editor == null ? EditorMetadata.defaults(id, type) : editor;
        semantics = semantics == null ? Semantics.NONE : semantics;
    }

    /** The shape before {@link Semantics}: the same members, without them. */
    public ParameterSchema(String id, ParameterType type, boolean required, String defaultValue,
            String description, List<String> allowedValues, List<ParameterSchema> nested, EditorMetadata editor) {
        this(id, type, required, defaultValue, description, allowedValues, nested, editor, Semantics.NONE);
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
                allowedValues, nested, metadata, semantics);
    }

    private ParameterSchema withSemantics(Semantics value) {
        return new ParameterSchema(id, type, required, defaultValue, description,
                allowedValues, nested, editor, value);
    }

    /** This parameter, holding a value of a grammar kind ({@link #KIND_STEPS}, {@link #KIND_CONDITION}, ...). */
    public ParameterSchema ofKind(String valueKind) {
        return withSemantics(new Semantics(valueKind, semantics.expression(), semantics.multiple(), semantics.scope()));
    }

    /**
     * This parameter, declared to accept an expression: the entry binds it at parse time with
     * {@code ParamReader.template} or {@code ParamReader.expression}, so its value may hold {@code ${...}}.
     */
    public ParameterSchema acceptingExpression() {
        return withSemantics(new Semantics(semantics.valueKind(), true, semantics.multiple(), semantics.scope()));
    }

    /** This parameter, declared to take one value or a list of values. */
    public ParameterSchema acceptingList() {
        return withSemantics(new Semantics(semantics.valueKind(), semantics.expression(), true, semantics.scope()));
    }

    /** This parameter, declaring the context variables the steps it holds can read beside the trigger's own. */
    public ParameterSchema opening(List<ContextVariable> variables) {
        return withSemantics(new Semantics(semantics.valueKind(), semantics.expression(), semantics.multiple(), variables));
    }

    /**
     * What a value is beyond its {@link ParameterType}. Additive and optional: an entry that declares none is
     * read as before.
     *
     * @param valueKind a grammar kind ({@link #KIND_STEPS} and the others), or blank
     * @param expression whether the value may be an expression ({@code ${...}}), bound at parse time
     * @param multiple whether one value or a list of values is accepted (a trigger filter)
     * @param scope the context variables the value's steps can read beside the trigger's own
     */
    public record Semantics(String valueKind, boolean expression, boolean multiple, List<ContextVariable> scope) {

        public static final Semantics NONE = new Semantics("", false, false, List.of());

        public Semantics {
            valueKind = valueKind == null ? "" : valueKind;
            scope = scope == null ? List.of() : List.copyOf(scope);
        }
    }

    /**
     * This parameter drawn with a named widget (for example {@code item}, {@code sound} or {@code variable}) instead
     * of the plain text its type implies; the type and the validation are unchanged (logic.md section 31).
     */
    public ParameterSchema withWidget(String widget, String referenceKind) {
        return withEditor(new EditorMetadata(editor.label(), editor.section(), editor.order(), editor.advanced(),
                widget, referenceKind, editor.example(), editor.module(), editor.category(), editor.icon(),
                editor.summary(), editor.options(), editor.min(), editor.max(), editor.unit()));
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
                String unit) {

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
            }

            /** Compatibility constructor for callers predating smart-input metadata. */
            public EditorMetadata(
                    String label, String section, int order, boolean advanced,
                    String widget, String referenceKind, String example,
                    boolean module, String category, String icon, String summary) {
                this(label, section, order, advanced, widget, referenceKind, example,
                        module, category, icon, summary, List.of(), null, null, "");
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
                        example, true, category, icon, summary, options, min, max, unit);
            }

            /** This metadata with labelled choices. */
            public EditorMetadata withOptions(List<ParameterSchema.EditorOption> choices) {
                return new EditorMetadata(label, section, order, advanced, widget, referenceKind,
                        example, module, category, icon, summary, choices, min, max, unit);
            }

            /** This metadata with a number range and unit ({@code null} bound = open). */
            public EditorMetadata withRange(Double lower, Double upper, String suffix) {
                return new EditorMetadata(label, section, order, advanced, widget, referenceKind,
                        example, module, category, icon, summary, options, lower, upper, suffix);
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
