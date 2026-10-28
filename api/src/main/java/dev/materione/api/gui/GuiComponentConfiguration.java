package dev.materione.api.gui;

import dev.materione.api.NamespacedId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One layer's component-specific fields, deeply immutable, with strict
 * non-coercing typed reads (the same rules as {@code MechanicConfiguration}).
 * The gui type removes the common layer fields ({@code id}, {@code type},
 * {@code name}, {@code visible}, {@code locked}, {@code opacity}) before the
 * component sees anything; everything else is the component's own schema.
 *
 * @param guiId the GUI resource being compiled
 * @param layerId the layer's id inside that GUI (for messages)
 * @param rows the GUI's row count (1..6); components that name slots bound-check against it
 * @param parameters the layer's remaining fields
 */
public record GuiComponentConfiguration(
        NamespacedId guiId, String layerId, int rows, Map<String, Object> parameters) {

    public GuiComponentConfiguration {
        Objects.requireNonNull(guiId, "guiId");
        Objects.requireNonNull(layerId, "layerId");
        parameters = immutableMap(parameters);
    }

    /** A whole number in {@code min..max}; absent reads as {@code fallback}. */
    public int integer(String key, int fallback, int min, int max) {
        return parameters.containsKey(key) ? integer(key, min, max) : fallback;
    }

    /** A whole number in {@code min..max}; absence is an error. */
    public int requiredInteger(String key, int min, int max) {
        if (!parameters.containsKey(key)) {
            throw invalid(key, "is required");
        }
        return integer(key, min, max);
    }

    private int integer(String key, int min, int max) {
        Object value = parameters.get(key);
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (Double.isFinite(decimal) && decimal == Math.rint(decimal)
                    && decimal >= min && decimal <= max) {
                return number.intValue();
            }
        }
        throw invalid(key, "must be an integer in " + min + ".." + max);
    }

    /** A finite number in {@code min..max}; absent reads as {@code fallback}. */
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

    /**
     * A non-blank string. An absent key reads as {@code fallback}, which may be
     * {@code null} for an optional field; a present key must be non-blank text.
     */
    public String text(String key, String fallback) {
        if (!parameters.containsKey(key)) {
            return fallback;
        }
        if (parameters.get(key) instanceof String text && !text.isBlank()) {
            return text;
        }
        throw invalid(key, "must be a non-blank string");
    }

    /** A required non-blank string. */
    public String requiredText(String key) {
        if (parameters.get(key) instanceof String text && !text.isBlank()) {
            return text;
        }
        throw invalid(key, "is required and must be a non-blank string");
    }

    /**
     * A colour as {@code #RRGGBB} (alpha 0xFF) or {@code #AARRGGBB}, returned
     * as ARGB. Absent reads as {@code fallback}.
     */
    public int color(String key, int fallback) {
        if (!parameters.containsKey(key)) {
            return fallback;
        }
        if (parameters.get(key) instanceof String text) {
            String hex = text.trim();
            if (hex.matches("#[0-9A-Fa-f]{6}")) {
                return 0xFF000000 | Integer.parseUnsignedInt(hex.substring(1), 16);
            }
            if (hex.matches("#[0-9A-Fa-f]{8}")) {
                return (int) Long.parseLong(hex.substring(1), 16);
            }
        }
        throw invalid(key, "must be a colour as #RRGGBB or #AARRGGBB");
    }

    /** A list of non-blank strings; absent reads as empty. */
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

    /** A nested mapping (a style block, say); absent reads as empty. */
    public Map<String, Object> mapping(String key) {
        Object value = parameters.getOrDefault(key, Map.of());
        if (value instanceof Map<?, ?> map) {
            return stringMap(map);
        }
        throw invalid(key, "must be a mapping");
    }

    private IllegalArgumentException invalid(String key, String message) {
        return new IllegalArgumentException(
                "layer " + layerId + ": " + key + " " + message);
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
            result.put(text, immutable(value));
        });
        return Collections.unmodifiableMap(result);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            return stringMap(map);
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
