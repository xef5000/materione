package dev.materione.api.logic;

import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link Recipe} written as data (logic.md §4.6, §30): the parameters and a list of rules with {@code {{name}}}
 * placeholders. The built-in recipes are YAML resources of this shape, and an addon that has its own YAML can use
 * the same form. Parsing is pure: it takes the mapping as a YAML loader gives it, with string keys (the {@code on:}
 * key normalised), and throws {@link IllegalArgumentException} with the reason when the template is malformed.
 *
 * <pre>{@code
 * name: Close button
 * description: Closes the GUI when the button is clicked.
 * params:
 *   layer: { type: string, description: The button layer's id }
 * rules:
 *   - on: gui.click
 *     layer: "{{layer}}"
 *     do:
 *       - gui.close
 * }</pre>
 *
 * <p>Placeholder rules. A string that is exactly {@code {{name}}} takes the parameter's typed value, so a number
 * stays a number. A {@code {{name}}} inside longer text takes the value as text. A blank optional text parameter
 * removes the key (or list item) that holds it, so an optional {@code currency:} is simply absent when blank. There
 * is no escape, and no placeholder is evaluated at run time: {@code ${...}} and {@code %...%} pass through untouched.
 */
public final class RecipeTemplate implements Recipe {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z][a-z0-9_]*)\\s*\\}\\}");
    private static final Pattern WHOLE = Pattern.compile("\\s*\\{\\{\\s*([a-z][a-z0-9_]*)\\s*\\}\\}\\s*");
    private static final Set<String> TOP_KEYS = Set.of("name", "description", "params", "rules");
    private static final Set<String> SPEC_KEYS =
            Set.of("type", "default", "min", "max", "scope", "value_type", "description");
    /** The marker a filled node returns when a blank optional parameter removes it. */
    private static final Object DROP = new Object();

    private final String id;
    private final RecipeDescription description;
    private final List<Object> rules;

    private RecipeTemplate(String id, RecipeDescription description, List<Object> rules) {
        this.id = id;
        this.description = description;
        this.rules = rules;
    }

    /**
     * Parses a recipe template.
     *
     * @param id the id the recipe registers under (its local name)
     * @param definition the mapping: {@code name}, {@code description}, {@code params} and {@code rules}
     * @throws IllegalArgumentException with the reason, when the template is malformed
     */
    public static RecipeTemplate parse(String id, Map<String, Object> definition) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definition, "definition");
        for (String key : definition.keySet()) {
            if (!TOP_KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown key '" + key + "' (a recipe has name, description, params and rules)");
            }
        }
        Object name = definition.get("name");
        if (!(name instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("a recipe needs a non-blank name");
        }
        RecipeDescription description = new RecipeDescription(
                text.trim(), optionalText(definition.get("description"), "description"), params(definition.get("params")));
        Set<String> names = new LinkedHashSet<>();
        description.params().forEach(param -> names.add(param.name()));
        if (!(definition.get("rules") instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("rules must be a non-empty list of rules");
        }
        var rules = new ArrayList<Object>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> rule)) {
                throw new IllegalArgumentException("rule " + (i + 1) + " is not a mapping");
            }
            checkPlaceholders(rule, names);
            rules.add(snapshot(rule));
        }
        return new RecipeTemplate(id, description, List.copyOf(rules));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public RecipeDescription describe() {
        return description;
    }

    /**
     * Fills the placeholders with {@code params}. A declared parameter that {@code params} omits takes its default;
     * a required one that is missing is an {@link IllegalArgumentException}.
     */
    @Override
    public List<Map<String, Object>> expand(Map<String, Object> params) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (RecipeParam param : description.params()) {
            Object value = params == null ? null : params.get(param.name());
            if (value == null) {
                if (param.required()) {
                    throw new IllegalArgumentException("parameter '" + param.name() + "' is required");
                }
                value = param.defaultValue();
            }
            values.put(param.name(), value);
        }
        var out = new ArrayList<Map<String, Object>>(rules.size());
        for (Object rule : rules) {
            Object filled = fill(rule, values);
            if (filled != DROP) {
                out.add(castRule(filled));
            }
        }
        return List.copyOf(out);
    }

    // --- parsing ----------------------------------------------------------------------------

    private static List<RecipeParam> params(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof Map<?, ?> specs)) {
            throw new IllegalArgumentException("params must be a mapping from each name to its spec");
        }
        var out = new ArrayList<RecipeParam>(specs.size());
        for (Map.Entry<?, ?> entry : specs.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> spec)) {
                throw new IllegalArgumentException("parameter '" + name + "' must be a mapping with a type");
            }
            out.add(param(name, spec));
        }
        return List.copyOf(out);
    }

    private static RecipeParam param(String name, Map<?, ?> spec) {
        for (Object key : spec.keySet()) {
            if (!SPEC_KEYS.contains(String.valueOf(key))) {
                throw new IllegalArgumentException("parameter '" + name + "' has an unknown key '" + key + "'");
            }
        }
        RecipeParamType type = type(name, spec.get("type"));
        VariableScope scope = null;
        VariableType valueType = null;
        if (type == RecipeParamType.VARIABLE) {
            String word = optionalText(spec.get("scope"), name + ".scope");
            scope = VariableScope.of(word == null ? "" : word).orElseThrow(() -> new IllegalArgumentException(
                    "parameter '" + name + "' needs scope player or server"));
            String kind = optionalText(spec.get("value_type"), name + ".value_type");
            valueType = VariableType.of(kind == null ? "" : kind).orElseThrow(() -> new IllegalArgumentException(
                    "parameter '" + name + "' needs value_type number, string or boolean"));
        }
        return new RecipeParam(name, type, spec.get("default"),
                optionalText(spec.get("description"), name + ".description"),
                bound(name, spec.get("min"), "min"), bound(name, spec.get("max"), "max"), scope, valueType);
    }

    private static RecipeParamType type(String name, Object raw) {
        String word = raw instanceof String text ? text.trim().toLowerCase(Locale.ROOT) : "";
        for (RecipeParamType type : RecipeParamType.values()) {
            if (type.id().equals(word)) {
                return type;
            }
        }
        throw new IllegalArgumentException("parameter '" + name
                + "' needs a type: string, number, integer, boolean or variable");
    }

    private static Double bound(String name, Object raw, String key) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("parameter '" + name + "' has a " + key + " that is not a number");
        }
        return number.doubleValue();
    }

    private static String optionalText(Object raw, String what) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException(what + " must be text");
        }
        return text;
    }

    /** A private copy of the template's maps and lists, so a caller's later edits cannot change the recipe. */
    private static Object snapshot(Object node) {
        if (node instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), snapshot(value)));
            return copy;
        }
        if (node instanceof List<?> list) {
            var copy = new ArrayList<Object>(list.size());
            list.forEach(item -> copy.add(snapshot(item)));
            return copy;
        }
        return node;
    }

    /** Every placeholder names a declared parameter, and no brace is left over. */
    private static void checkPlaceholders(Object node, Set<String> names) {
        if (node instanceof String text) {
            Matcher found = PLACEHOLDER.matcher(text);
            while (found.find()) {
                if (!names.contains(found.group(1))) {
                    throw new IllegalArgumentException("'{{" + found.group(1) + "}}' names no declared parameter");
                }
            }
            String rest = PLACEHOLDER.matcher(text).replaceAll("");
            if (rest.contains("{{") || rest.contains("}}")) {
                throw new IllegalArgumentException("malformed placeholder in '" + text + "' (write {{name}})");
            }
        } else if (node instanceof Map<?, ?> map) {
            map.values().forEach(value -> checkPlaceholders(value, names));
        } else if (node instanceof List<?> list) {
            list.forEach(item -> checkPlaceholders(item, names));
        }
    }

    // --- expansion --------------------------------------------------------------------------

    private Object fill(Object node, Map<String, Object> values) {
        if (node instanceof String text) {
            return fillText(text, values);
        }
        if (node instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object value = fill(entry.getValue(), values);
                if (value != DROP) {
                    out.put(String.valueOf(entry.getKey()), value);
                }
            }
            return out;
        }
        if (node instanceof List<?> list) {
            var out = new ArrayList<Object>(list.size());
            for (Object item : list) {
                Object value = fill(item, values);
                if (value != DROP) {
                    out.add(value);
                }
            }
            return out;
        }
        return node;
    }

    private Object fillText(String text, Map<String, Object> values) {
        Matcher whole = WHOLE.matcher(text);
        if (whole.matches()) {
            String name = whole.group(1);
            Object value = values.get(name);
            if (value instanceof String blank && blank.isBlank() && !description.param(name).required()) {
                return DROP;
            }
            return value;
        }
        Matcher found = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (found.find()) {
            found.appendReplacement(out, Matcher.quoteReplacement(asText(values.get(found.group(1)))));
        }
        found.appendTail(out);
        return out.toString();
    }

    /** A value inside longer text: a whole number reads without its decimal point. */
    private static String asText(Object value) {
        if (value instanceof Double number && Double.isFinite(number) && number == Math.rint(number)) {
            return Long.toString(number.longValue());
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castRule(Object filled) {
        return (Map<String, Object>) filled;
    }
}
