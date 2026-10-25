package dev.materione.api.mechanics;

import dev.materione.api.NamespacedId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deeply immutable YAML parameters with strict, non-coercing typed reads. */
public record MechanicConfiguration(NamespacedId itemId, Map<String, Object> parameters) {
    public MechanicConfiguration {
        Objects.requireNonNull(itemId, "itemId");
        parameters = immutableMap(parameters);
    }

    public int integer(String key, int fallback, int min, int max) {
        Object value = parameters.getOrDefault(key, fallback);
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (Double.isFinite(decimal) && decimal == Math.rint(decimal)
                    && decimal >= min && decimal <= max) {
                return number.intValue();
            }
        }
        throw invalid(key, "must be an integer in " + min + ".." + max);
    }

    public double decimal(String key, double fallback, double min, double max) {
        Object value = parameters.getOrDefault(key, fallback);
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (Double.isFinite(decimal) && decimal >= min && decimal <= max) {
                return decimal;
            }
        }
        throw invalid(key, "must be a finite number in " + min + ".." + max);
    }

    public boolean bool(String key, boolean fallback) {
        Object value = parameters.getOrDefault(key, fallback);
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw invalid(key, "must be a boolean");
    }

    public String text(String key, String fallback) {
        Object value = parameters.getOrDefault(key, fallback);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw invalid(key, "must be a non-blank string");
    }

    public List<String> strings(String key) {
        Object value = parameters.getOrDefault(key, List.of());
        if (!(value instanceof List<?> list)) {
            throw invalid(key, "must be a list of strings");
        }
        var result = new ArrayList<String>();
        for (Object entry : list) {
            if (!(entry instanceof String text) || text.isBlank()) {
                throw invalid(key, "must contain non-blank strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    public List<MechanicConfiguration> sections(String key) {
        Object value = parameters.getOrDefault(key, List.of());
        if (!(value instanceof List<?> list)) {
            throw invalid(key, "must be a list of mappings");
        }
        var result = new ArrayList<MechanicConfiguration>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw invalid(key, "must contain mappings");
            }
            result.add(new MechanicConfiguration(itemId, stringMap(map)));
        }
        return List.copyOf(result);
    }

    private static IllegalArgumentException invalid(String key, String message) {
        return new IllegalArgumentException(key + " " + message);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        Objects.requireNonNull(source, "parameters");
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> result.put(Objects.requireNonNull(key, "parameter key"), immutable(value)));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("parameter names must be strings");
            }
            result.put(text, value);
        });
        return result;
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            return immutableMap(stringMap(map));
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<Object>();
            list.forEach(entry -> result.add(immutable(entry)));
            return Collections.unmodifiableList(result);
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("unsupported parameter value: " + value.getClass().getSimpleName());
    }
}
