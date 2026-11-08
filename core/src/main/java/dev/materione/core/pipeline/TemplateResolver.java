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

    /**
     * Pool key prefix of an item that may serve as a template: item {@code demo:sword} is
     * {@code item|demo:sword}, a template {@code demo:sword} stays {@code demo:sword}.
     */
    public static final String ITEM_PREFIX = "item|";

    private static final String CYCLE_PREFIX = "template chain revisits ";

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
     * Merges {@code template:} inheritance into {@code definition}; only templates can be named.
     *
     * @return the merged map, or {@code null} when the chain is unknown,
     *         cyclic or diamond-shaped
     */
    public static LinkedHashMap<String, Object> merge(
            Map<String, Object> definition, String defaultNamespace, Map<String, Map<String, Object>> templates) {
        return merge(definition, defaultNamespace, templates, false);
    }

    /**
     * {@link #merge(Map, String, Map)}, where an item may also be named when {@code itemsServe} (the
     * definition is an item, plugin.md section 3.1).
     */
    public static LinkedHashMap<String, Object> merge(
            Map<String, Object> definition, String defaultNamespace, Map<String, Map<String, Object>> templates,
            boolean itemsServe) {
        Resolution resolution = resolve(definition, defaultNamespace, templates, itemsServe);
        return resolution.merged() == null ? null : new LinkedHashMap<>(resolution.merged());
    }

    /** Resolves the chain of a definition that can name templates only. */
    public static Resolution resolve(
            Map<String, Object> definition, String defaultNamespace, Map<String, Map<String, Object>> templates) {
        return resolve(definition, defaultNamespace, templates, false);
    }

    /**
     * Resolves the chain, recording which template supplied each inherited key.
     *
     * <p>Precedence (plugin.md section 3.1): a reference is looked up as a template first; only when no
     * template has that id, and only while the definition at that hop is an item ({@code itemsServe} for
     * the first hop, then for as long as the chain walks through items), as an item. Item entries live in
     * the pool under {@link #ITEM_PREFIX}, so they can never be mistaken for templates. A template's own
     * {@code template:} names templates only.
     */
    public static Resolution resolve(
            Map<String, Object> definition, String defaultNamespace, Map<String, Map<String, Object>> templates,
            boolean itemsServe) {
        var merged = new LinkedHashMap<String, Object>();
        var sources = new LinkedHashMap<String, String>();
        var chain = new ArrayList<String>();
        String templateRef = text(definition, "template");
        if (templateRef != null) {
            String failure = resolveTemplate(
                    shortRef(defaultNamespace, templateRef), itemsServe, templates, new HashSet<>(), merged, sources,
                    chain);
            if (failure != null) {
                return new Resolution(null, Map.of(), chain, failure);
            }
        }
        merged.putAll(definition);
        definition.keySet().forEach(sources::remove);
        return new Resolution(merged, sources, chain, null);
    }

    /** True when the resolution failed because the chain loops back on itself (MTR-0093). */
    public static boolean isCycle(Resolution resolution) {
        return resolution.failure() != null && resolution.failure().startsWith(CYCLE_PREFIX);
    }

    /** The pool entry a reference names for a definition that may use items: the template, else the item. */
    public static Map<String, Object> lookup(
            Map<String, Map<String, Object>> pool, String ref, boolean itemsServe) {
        Map<String, Object> template = pool.get(ref);
        return template != null || !itemsServe ? template : pool.get(ITEM_PREFIX + ref);
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
     * revisited node is a cycle (a chain has one parent per node, so there
     * are no diamonds) and is rejected. A template and an item of the same
     * id are different nodes.
     *
     * @return null on success, else the failure reason
     */
    private static String resolveTemplate(
            String ref, boolean itemsServe, Map<String, Map<String, Object>> templates, Set<String> visited,
            Map<String, Object> merged, Map<String, String> sources, List<String> chain) {
        Map<String, Object> template = templates.get(ref);
        boolean isItem = false;
        if (template == null && itemsServe) {
            template = templates.get(ITEM_PREFIX + ref);
            isItem = template != null;
        }
        if (!visited.add((isItem ? ITEM_PREFIX : "") + ref)) {
            return CYCLE_PREFIX + ref + " (" + String.join(" -> ", chain) + " -> " + ref + ")";
        }
        chain.add(ref);
        if (template == null) {
            return "unknown template " + ref;
        }
        String parentRef = text(template, "template");
        if (parentRef != null) {
            // a short parent reference resolves in the namespace of the template that names it
            String failure = resolveTemplate(
                    shortRef(ref.substring(0, ref.indexOf(':')), parentRef),
                    isItem, templates, visited, merged, sources, chain);
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
