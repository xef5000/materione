package dev.materione.core.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Template inheritance shared by every definition-bearing content type
 * (runtime-systems §3.2): a template is a partial definition; chains
 * resolve parent-first and the definition itself wins; unknown, cyclic or
 * diamond references fail (null) so the pipeline quarantines the resource.
 *
 * <p>{@link #resolve} is the one resolver: content types merge through it
 * ({@code dev.materione.content.Templates} is a facade) and the editor's
 * effective-value/provenance view reads it, so the editor cannot explain
 * inheritance differently from how the pipeline compiles it.
 */
public final class TemplateResolver {

    private TemplateResolver() {}

    /**
     * The outcome of resolving one definition's {@code template:} chain.
     *
     * @param merged  the effective top-level map, or {@code null} when the chain failed
     * @param sources per effective top-level key, the template id that supplied it;
     *                keys the definition sets itself are absent (merge is shallow, so
     *                provenance is exact at the top level)
     * @param chain   template ids nearest-first, as far as resolution got
     * @param failure why resolution failed, or {@code null}
     */
    public record Resolution(
            Map<String, Object> merged, Map<String, String> sources, List<String> chain, String failure) {

        public Resolution {
            merged = merged == null ? null : Collections.unmodifiableMap(merged);
            sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
            chain = List.copyOf(chain);
        }
    }

    /**
     * Merges {@code template:} inheritance into {@code definition}.
     *
     * @return the merged map, or {@code null} when the chain is unknown,
     *         cyclic or diamond-shaped
     */
    public static LinkedHashMap<String, Object> merge(
            Map<String, Object> definition, String defaultNamespace, Map<String, Map<String, Object>> templates) {
        Resolution resolution = resolve(definition, defaultNamespace, templates);
        return resolution.merged() == null ? null : new LinkedHashMap<>(resolution.merged());
    }

    /** Resolves the chain, recording which template supplied each inherited key. */
    public static Resolution resolve(
            Map<String, Object> definition, String defaultNamespace, Map<String, Map<String, Object>> templates) {
        var merged = new LinkedHashMap<String, Object>();
        var sources = new LinkedHashMap<String, String>();
        var chain = new ArrayList<String>();
        String templateRef = text(definition, "template");
        if (templateRef != null) {
            String failure = resolveTemplate(
                    shortRef(defaultNamespace, templateRef), templates, new HashSet<>(), merged, sources, chain);
            if (failure != null) {
                return new Resolution(null, Map.of(), chain, failure);
            }
        }
        merged.putAll(definition);
        definition.keySet().forEach(sources::remove);
        return new Resolution(merged, sources, chain, null);
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

    /**
     * Recursive chain resolution into {@code merged}, parent-first; a
     * revisited template is a diamond — rejected.
     *
     * @return null on success, else the failure reason
     */
    private static String resolveTemplate(
            String ref, Map<String, Map<String, Object>> templates, Set<String> visited,
            Map<String, Object> merged, Map<String, String> sources, List<String> chain) {
        if (!visited.add(ref)) {
            return "template chain revisits " + ref;
        }
        chain.add(ref);
        Map<String, Object> template = templates.get(ref);
        if (template == null) {
            return "unknown template " + ref;
        }
        String parentRef = text(template, "template");
        if (parentRef != null) {
            String failure = resolveTemplate(parentRef, templates, visited, merged, sources, chain);
            if (failure != null) {
                return failure;
            }
        }
        merged.putAll(template);
        for (String key : template.keySet()) {
            sources.put(key, ref);
        }
        return null;
    }
}
