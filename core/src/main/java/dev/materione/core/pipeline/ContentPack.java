package dev.materione.core.pipeline;

import java.nio.file.Path;
import java.util.List;

/**
 * One discovered pack under {@code content/packs/<id>/} with its manifest
 * and the resources parsed so far. A pack with an invalid manifest still
 * appears here (fail-soft, D6) — its resources are quarantined, not hidden.
 */
public record ContentPack(String id, Path directory, PackManifest manifest, List<ContentResource> resources) {

    public ContentPack {
        resources = List.copyOf(resources);
    }
}
