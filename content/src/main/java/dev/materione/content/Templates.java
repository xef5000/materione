package dev.materione.content;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Template inheritance shared by every definition-bearing content type
 * (runtime-systems §3.2): a template is a partial definition; chains
 * resolve parent-first and the definition itself wins; unknown, cyclic or
 * diamond references fail (null) so the pipeline quarantines the resource.
 */
public final class Templates {

    private Templates() {}

    /**
     * Merges {@code template:} inheritance into {@code definition}.
     *
     * @return the merged map, or {@code null} when the chain is unknown,
     *         cyclic or diamond-shaped
     */
    public static LinkedHashMap<String, Object> merge(
            Map<String, Object> definition, String defaultNamespace, Map<String, Map<String, Object>> templates) {
        var merged = new LinkedHashMap<String, Object>();
        String templateRef = text(definition, "template");
        if (templateRef != null) {
            Map<String, Object> base = resolveTemplate(shortRef(defaultNamespace, templateRef), templates, new HashSet<>());
            if (base == null) {
                return null;
            }
            merged.putAll(base);
        }
        merged.putAll(definition);
        return merged;
    }

    /** Short references resolve against the resource's own namespace. */
    public static String shortRef(String defaultNamespace, String raw) {
        return raw.contains(":") ? raw : defaultNamespace + ":" + raw;
    }

    /** The named field when it is a non-blank string, else null. */
    public static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** Recursive chain resolution; a revisited template is a diamond — rejected. */
    private static Map<String, Object> resolveTemplate(
            String ref, Map<String, Map<String, Object>> templates, Set<String> visited) {
        if (!visited.add(ref)) {
            return null;
        }
        Map<String, Object> template = templates.get(ref);
        if (template == null) {
            return null;
        }
        var merged = new LinkedHashMap<String, Object>();
        String parentRef = text(template, "template");
        if (parentRef != null) {
            Map<String, Object> parent = resolveTemplate(parentRef, templates, visited);
            if (parent == null) {
                return null;
            }
            merged.putAll(parent);
        }
        merged.putAll(template);
        return merged;
    }
}
