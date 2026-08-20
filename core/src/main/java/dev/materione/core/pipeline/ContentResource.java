package dev.materione.core.pipeline;

import dev.materione.api.NamespacedId;
import dev.materione.core.document.LosslessYamlDocument;
import java.nio.file.Path;

/**
 * One content resource (an item, a template, a recipe…) as it moved through
 * parse: identity, content type, source file for lineage, and the lossless
 * document. A file holds resources of exactly one content type (master §7.2).
 */
public record ContentResource(
        NamespacedId id,
        String contentType,
        Path sourceFile,
        LosslessYamlDocument document) {}
