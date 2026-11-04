package dev.materione.behavior.logic.recipe;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.RecipeDescription;
import dev.materione.api.logic.RecipeParam;
import dev.materione.api.logic.RecipeParamType;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.behavior.logic.LogicLookup;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Checks an {@code instantiate} request's parameters against a recipe's description (logic.md §30). Every problem is
 * reported at once, each as {@code MTR-0086}, so the author fixes them in one pass; a request with any problem expands
 * nothing. A variable parameter is checked against the declaration the lookup holds, so an undeclared name, or one
 * with the wrong scope or value type, is refused here rather than written as a step that fails to load.
 */
final class RecipeParams {

    private RecipeParams() {}

    /**
     * The typed parameters: every declared name with its value, or its default when the author gave none.
     *
     * @return the typed values, or every problem found
     */
    static ApiResult<Map<String, Object>> resolve(
            RecipeDescription description, Map<String, Object> given, LogicLookup lookup) {
        List<Diagnostic> problems = new ArrayList<>();
        for (String key : given.keySet()) {
            if (description.param(key) == null) {
                problems.add(refuse("'" + key + "' is not a parameter of " + description.name() + ", which takes "
                        + names(description)));
            }
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        for (RecipeParam param : description.params()) {
            Object raw = given.get(param.name());
            if (raw == null) {
                if (param.required()) {
                    problems.add(refuse(param.name() + " is required"));
                } else {
                    typed.put(param.name(), param.defaultValue());
                }
                continue;
            }
            Object value = check(param, raw, lookup, problems);
            if (value != null) {
                typed.put(param.name(), value);
            }
        }
        return problems.isEmpty() ? ApiResult.ok(typed) : ApiResult.fail(problems);
    }

    /** One given value, typed; null when it is refused (its problem is already in {@code problems}). */
    private static Object check(RecipeParam param, Object raw, LogicLookup lookup, List<Diagnostic> problems) {
        if (param.type() == RecipeParamType.VARIABLE) {
            return variable(param, raw, lookup, problems);
        }
        Optional<Object> value = param.type().coerce(raw);
        if (value.isEmpty()) {
            problems.add(refuse(param.name() + " must be " + kind(param.type())));
            return null;
        }
        if (value.get() instanceof String text && text.isBlank() && param.required()) {
            problems.add(refuse(param.name() + " must not be blank"));
            return null;
        }
        if (value.get() instanceof Number number && !inRange(param, number.doubleValue())) {
            problems.add(refuse(param.name() + " must be " + range(param)));
            return null;
        }
        return value.get();
    }

    private static Object variable(RecipeParam param, Object raw, LogicLookup lookup, List<Diagnostic> problems) {
        NamespacedId id;
        try {
            id = NamespacedId.parse(String.valueOf(raw).trim());
        } catch (IllegalArgumentException malformed) {
            problems.add(refuse(param.name() + " must name a variable as pack:key"));
            return null;
        }
        Optional<VariableDeclaration> declaration = lookup.variable(id);
        if (declaration.isEmpty()) {
            problems.add(refuse(param.name() + ": '" + id + "' is not a declared variable "
                    + "(declare it in a variables file of its pack)"));
            return null;
        }
        VariableDeclaration found = declaration.get();
        if (found.scope() != param.scope() || found.type() != param.valueType()) {
            problems.add(refuse(param.name() + ": '" + id + "' is declared " + found.scope().id() + " "
                    + found.type().id() + "; the recipe needs " + param.scope().id() + " "
                    + param.valueType().id()));
            return null;
        }
        return id.toString();
    }

    private static boolean inRange(RecipeParam param, double value) {
        return (param.min() == null || value >= param.min()) && (param.max() == null || value <= param.max());
    }

    private static String kind(RecipeParamType type) {
        return switch (type) {
            case STRING -> "text";
            case NUMBER -> "a number";
            case INTEGER -> "a whole number";
            case BOOLEAN -> "true or false";
            case VARIABLE -> "a variable as pack:key";
        };
    }

    private static String range(RecipeParam param) {
        if (param.min() != null && param.max() != null) {
            return "between " + number(param.min()) + " and " + number(param.max());
        }
        return param.min() != null ? "at least " + number(param.min()) : "at most " + number(param.max());
    }

    /** A whole number reads without its decimal point. */
    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private static String names(RecipeDescription description) {
        if (description.params().isEmpty()) {
            return "no parameters";
        }
        return description.params().stream().map(RecipeParam::name).collect(Collectors.joining(", "));
    }

    private static Diagnostic refuse(String message) {
        return Diagnostic.error(DiagnosticCodes.RECIPE_PARAMETER_REFUSED, message);
    }
}
