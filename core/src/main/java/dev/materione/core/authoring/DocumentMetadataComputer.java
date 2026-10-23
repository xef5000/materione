package dev.materione.core.authoring;

import java.util.Map;

/**
 * Server-computed, read-only document metadata (replaces the web editor's
 * {@code ComputedMeta} seam): data the runtime owns — a glyph's allocated
 * codepoint — that a form should show but the canonical file must never
 * contain (D7). The implementation lives in the plugin (it reads the
 * allocation table); authoring is agnostic to what is computed.
 *
 * <p>Null-safe by contract: a null computer or a null return means "nothing
 * applies to this document".
 */
@FunctionalInterface
public interface DocumentMetadataComputer {

    /**
     * @param pack   pack id the document lives in
     * @param path   pack-relative document path
     * @param parsed the parsed document tree; never null when called
     * @return metadata to attach to the document view, or null when none applies
     */
    Map<String, Object> compute(String pack, String path, Map<String, Object> parsed);
}
