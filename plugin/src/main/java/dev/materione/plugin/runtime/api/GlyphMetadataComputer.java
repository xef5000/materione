package dev.materione.plugin.runtime.api;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.AllocatedRange;
import dev.materione.core.authoring.DocumentMetadataComputer;
import dev.materione.core.text.GlyphCodepoints;
import dev.materione.plugin.runtime.PipelineService;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-computed metadata for the studio: a glyph's allocated codepoint
 * (api.md §3.3). The allocation table owns it (D7) — the form shows it, the
 * file on disk never contains it. Moved from the editor transport so the
 * transport and the authoring API share one implementation.
 */
public final class GlyphMetadataComputer implements DocumentMetadataComputer {

    private final PipelineService pipeline;

    public GlyphMetadataComputer(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public Map<String, Object> compute(String pack, String path, Map<String, Object> parsed) {
        if (!path.startsWith("glyphs/") || pipeline.allocation() == null || parsed == null) {
            return null;
        }
        Map<String, Object> glyphs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            AllocatedRange run;
            try {
                run = pipeline.allocation().allocationOf(
                        NamespacedId.parse(pack + ":" + entry.getKey()),
                        dev.materione.content.glyphs.GlyphType.ALLOCATION_KIND)
                        .map(AllocatedRange::parse)
                        .orElse(null);
            } catch (IllegalArgumentException malformedId) {
                continue;
            }
            if (run == null) {
                continue;
            }
            int codepoint = GlyphCodepoints.of(run.start());
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("codepoint", codepoint);
            info.put("count", run.count());
            info.put("char", String.valueOf((char) codepoint));
            glyphs.put(entry.getKey(), info);
        }
        if (glyphs.isEmpty()) {
            return null;
        }
        return Map.of("glyphs", glyphs);
    }
}
