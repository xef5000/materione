package dev.materione.api.logic;

import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.api.registry.ParameterSchema;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One declared parameter of a {@link Recipe} (logic.md §4.6, §30): a name, a kind, and either a default (the
 * parameter is optional) or none (it is required). A {@link RecipeParamType#NUMBER} or
 * {@link RecipeParamType#INTEGER} may carry a range; a {@link RecipeParamType#VARIABLE} names the declaration it
 * must refer to by scope and value type.
 *
 * @param name the parameter's name: lower-case letters, digits and underscores, starting with a letter
 * @param type the kind of value it takes
 * @param defaultValue the value used when the author gives none, already of the kind's type; null when required
 * @param description one sentence the editor shows beside the field
 * @param min the smallest number a {@code NUMBER} or {@code INTEGER} may take; null for no bound
 * @param max the largest number a {@code NUMBER} or {@code INTEGER} may take; null for no bound
 * @param scope a {@code VARIABLE}'s declared scope; null for every other kind
 * @param valueType a {@code VARIABLE}'s declared value type; null for every other kind
 */
public record RecipeParam(
        String name,
        RecipeParamType type,
        Object defaultValue,
        String description,
        Double min,
        Double max,
        VariableScope scope,
        VariableType valueType) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");

    public RecipeParam {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("parameter name '" + name
                    + "' must be lower-case letters, digits and underscores, starting with a letter");
        }
        description = description == null ? "" : description;
        if (defaultValue != null) {
            defaultValue = type.coerce(defaultValue).orElseThrow(() -> new IllegalArgumentException(
                    "the default of '" + name + "' does not fit a " + type.id()));
        }
        boolean ranged = type == RecipeParamType.NUMBER || type == RecipeParamType.INTEGER;
        if (!ranged && (min != null || max != null)) {
            throw new IllegalArgumentException("'" + name + "' is a " + type.id() + " and takes no min or max");
        }
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException("'" + name + "' has min above max");
        }
        boolean variable = type == RecipeParamType.VARIABLE;
        if (variable && (scope == null || valueType == null)) {
            throw new IllegalArgumentException("'" + name + "' is a variable and names its scope and value type");
        }
        if (!variable && (scope != null || valueType != null)) {
            throw new IllegalArgumentException("'" + name + "' is a " + type.id() + " and takes no scope or value type");
        }
    }

    /** A required parameter of {@code type}, with no range. */
    public static RecipeParam required(String name, RecipeParamType type, String description) {
        return new RecipeParam(name, type, null, description, null, null, null, null);
    }

    /** An optional parameter of {@code type}, with the default the author gets when they give none. */
    public static RecipeParam optional(String name, RecipeParamType type, Object defaultValue, String description) {
        return new RecipeParam(name, type, defaultValue, description, null, null, null, null);
    }

    /** The same parameter with a range (for {@code NUMBER} and {@code INTEGER}). */
    public RecipeParam ranged(double low, double high) {
        return new RecipeParam(name, type, defaultValue, description, low, high, scope, valueType);
    }

    /** A required parameter that names a declared variable of {@code scope} and {@code valueType}. */
    public static RecipeParam variable(String name, VariableScope scope, VariableType valueType, String description) {
        return new RecipeParam(name, RecipeParamType.VARIABLE, null, description, null, null, scope, valueType);
    }

    /** A parameter is required when it has no default. */
    public boolean required() {
        return defaultValue == null;
    }

    /** The registry schema the editor's form reads for this parameter. */
    public ParameterSchema schema() {
        String fallback = defaultValue == null ? null : String.valueOf(defaultValue);
        return new ParameterSchema(name, type.schemaType(), required(), fallback, description, null, null);
    }
}
