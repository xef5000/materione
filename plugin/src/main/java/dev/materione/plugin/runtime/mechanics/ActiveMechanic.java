package dev.materione.plugin.runtime.mechanics;

import java.util.Map;

/**
 * One mechanic declaration on one item, resolved against the mechanics
 * registry at apply: the registry entry exists and its parameters passed
 * validation. {@code params} is the raw YAML parameter map — natives read
 * their typed values out of it (they validated the shape at resolve time).
 */
public record ActiveMechanic(String name, Map<String, Object> params) {

    public ActiveMechanic {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    // --- typed parameter reads shared by resolver and service ---------------

    public static int intParam(Map<String, Object> params, String key, int fallback) {
        Object value = params.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    public static double doubleParam(Map<String, Object> params, String key, double fallback) {
        Object value = params.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    public static boolean boolParam(Map<String, Object> params, String key, boolean fallback) {
        Object value = params.get(key);
        return value instanceof Boolean b ? b : fallback;
    }

    public static String textParam(Map<String, Object> params, String key, String fallback) {
        Object value = params.get(key);
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }
}
