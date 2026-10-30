package dev.materione.behavior.flow;

import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.variable.CompileEnvironment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reading the author's YAML shapes: the same few moves for actions, predicates and control steps. */
final class Shapes {

    private Shapes() {}

    /** A list as written, or a single value standing for a one-element list; null is empty. */
    static List<Object> listOf(Object value) {
        if (value instanceof List<?> list) {
            return List.copyOf(list);
        }
        return value != null ? List.of(value) : List.of();
    }

    /** The mapping with its non-string keys dropped, in authored order. */
    static Map<String, Object> stringMap(Map<?, ?> raw) {
        var out = new LinkedHashMap<String, Object>();
        for (var entry : raw.entrySet()) {
            if (entry.getKey() instanceof String key) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }

    /**
     * Normalises the author's two spellings: a mapping is the parameters, a scalar is the value of
     * the type's shorthand parameter.
     *
     * @param what the registered key, for the error message
     * @param shorthand the parameter a bare value stands for, or null when the type takes a mapping only
     * @throws IllegalArgumentException when a bare value cannot be placed
     */
    static Map<String, Object> paramsOf(String what, String shorthand, Object payload) {
        if (payload instanceof Map<?, ?> mapping) {
            return stringMap(mapping);
        }
        var params = new LinkedHashMap<String, Object>();
        if (payload != null) {
            if (shorthand == null) {
                throw new IllegalArgumentException(
                        "takes a mapping of parameters, not a bare value (e.g. " + what + ": { … })");
            }
            params.put(shorthand, payload);
        }
        return params;
    }

    /** A reader over {@code params} that notes every template it compiles. */
    static ParamReader reader(Map<String, Object> params, RecordingExpressions recorder, CompileEnvironment env) {
        return ParamReader.of(params, recorder, env);
    }

    /** A deeply immutable copy of a YAML value (lists and maps copied, scalars shared). */
    static Object freeze(Object value) {
        if (value instanceof Map<?, ?> mapping) {
            var copy = new LinkedHashMap<Object, Object>();
            mapping.forEach((key, entry) -> copy.put(key, freeze(entry)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new java.util.ArrayList<Object>(list.size());
            list.forEach(entry -> copy.add(freeze(entry)));
            return java.util.Collections.unmodifiableList(copy);
        }
        return value;
    }

    /** True for an integral number that is not a boolean (YAML ints arrive as Integer or Long). */
    static boolean isWhole(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte;
    }
}
