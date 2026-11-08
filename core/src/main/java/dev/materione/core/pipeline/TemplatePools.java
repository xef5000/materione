package dev.materione.core.pipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The template pool every parse reads (plugin.md section 3.1): each template under its
 * {@code pack:id}, and each item under {@link TemplateResolver#ITEM_PREFIX} + {@code pack:id}, so an
 * item can serve as the template of another item without ever shadowing a template. One builder for
 * the pack compile, the runtime shadow build and validation, so they cannot disagree about what a
 * {@code template:} reference names.
 */
public final class TemplatePools {

    private TemplatePools() {}

    /** Templates and items of {@code packs}; on a duplicate id the later resource wins, as templates always did. */
    public static Map<String, Map<String, Object>> of(List<ContentPack> packs) {
        var pool = new LinkedHashMap<String, Map<String, Object>>();
        for (ContentPack pack : packs) {
            for (ContentResource resource : pack.resources()) {
                boolean template = "template".equals(resource.contentType());
                if (!template && !"item".equals(resource.contentType())) {
                    continue;
                }
                if (resource.document().data().get(resource.id().value()) instanceof Map<?, ?> mapping) {
                    @SuppressWarnings("unchecked")
                    var raw = (Map<String, Object>) mapping;
                    pool.put((template ? "" : TemplateResolver.ITEM_PREFIX) + resource.id(), raw);
                }
            }
        }
        return pool;
    }
}
