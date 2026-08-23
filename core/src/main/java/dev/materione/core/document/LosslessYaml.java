package dev.materione.core.document;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Entry point of the lossless YAML layer (master architecture §5.6): a
 * document loaded here and saved untouched is <em>byte-identical</em> —
 * comments, ordering, blank lines, unknown fields and line terminators
 * included. Unknown fields are preserved on purpose: a newer pack's fields
 * must survive an older plugin round-trip (forward compatibility).
 *
 * <p>Two views over the same text:
 * <ul>
 *   <li><strong>Data view</strong> — plain {@code Map<String,Object>} via
 *       SnakeYAML; used by validation, graph build and compilers.</li>
 *   <li><strong>Formatting view</strong> — a line tree keyed by mapping path;
 *       used by targeted edits. An edit touches exactly the lines it must:
 *       pack repositories stay git-reviewable (minimal diffs).</li>
 * </ul>
 *
 * <p>Editing supports block-style mappings of scalars (the shape of pack
 * documents, master §7.2). Flow style, anchors and multi-document streams
 * round-trip untouched but are not individually editable — they surface as
 * opaque text within their line.
 */
public final class LosslessYaml {

    private LosslessYaml() {}

    /**
     * Parses raw text. Never throws for content-level YAML problems: a
     * document that SnakeYAML rejects carries {@link LosslessYamlDocument#parseError()}
     * and an empty data view — the pipeline quarantines the file (D6), it
     * does not crash.
     */
    public static LosslessYamlDocument parse(String raw) {
        String parseError = null;
        Object data;
        try {
            data = new Yaml().load(raw);
        } catch (YAMLException e) {
            parseError = e.getMessage();
            data = null;
        }
        if (data != null && !(data instanceof java.util.Map)) {
            parseError = "document is not a mapping";
            data = null;
        }
        @SuppressWarnings("unchecked")
        var dataMap = (java.util.Map<String, Object>) (data == null ? java.util.Map.of() : data);
        return new LosslessYamlDocument(raw, dataMap, parseError);
    }

    /**
     * A data-view mapping with YAML-1.1 boolean keys normalized back to
     * their canonical text ({@code true}→{@code "on"}, {@code false}→
     * {@code "off"}); non-string non-boolean keys are dropped. SnakeYAML
     * implements YAML 1.1, where a bare {@code on:} / {@code off:} /
     * {@code yes:} / {@code no:} mapping key loads as a boolean — without
     * this, the ECA selector key {@code on:} would vanish for every author
     * who writes it unquoted.
     */
    public static java.util.Map<String, Object> stringKeyed(java.util.Map<?, ?> source) {
        var out = new java.util.LinkedHashMap<String, Object>();
        for (java.util.Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof Boolean flag) {
                out.put(flag ? "on" : "off", entry.getValue());
            } else if (entry.getKey() instanceof String text) {
                out.put(text, entry.getValue());
            }
        }
        return out;
    }
}
