package dev.materione.content;

import dev.materione.core.pipeline.TemplateResolver;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Template inheritance shared by every definition-bearing content type
 * (runtime-systems §3.2): a template is a partial definition; chains
 * resolve parent-first and the definition itself wins; unknown, cyclic or
 * diamond references fail (null) so the pipeline quarantines the resource.
 * A facade over {@link TemplateResolver}, the one resolver the editor's
 * inheritance view also reads.
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
        return TemplateResolver.merge(definition, defaultNamespace, templates);
    }

    /** Short references resolve against the resource's own namespace. */
    public static String shortRef(String defaultNamespace, String raw) {
        return TemplateResolver.shortRef(defaultNamespace, raw);
    }

    /** The named field when it is a non-blank string, else null. */
    public static String text(Map<String, Object> map, String key) {
        return TemplateResolver.text(map, key);
    }
}
