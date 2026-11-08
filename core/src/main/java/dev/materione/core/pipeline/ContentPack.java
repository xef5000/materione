package dev.materione.core.pipeline;

import java.nio.file.Path;
import java.util.List;

/**
 * One discovered pack under {@code content/packs/<id>/} with its manifest
 * and the resources parsed so far. A pack with an invalid manifest still
 * appears here (fail-soft, D6) — its resources are quarantined, not hidden.
 *
 * <p>{@code builtin} marks a pack shipped inside the plugin jar (master §7.3, {@link BuiltinPacks}): read-only, never
 * on disk under {@code content/packs}, so nothing may write to its {@link #directory()}.
 */
public record ContentPack(
        String id, Path directory, PackManifest manifest, List<ContentResource> resources, boolean builtin) {

    public ContentPack {
        resources = List.copyOf(resources);
    }

    /** A pack from {@code content/packs}. */
    public ContentPack(String id, Path directory, PackManifest manifest, List<ContentResource> resources) {
        this(id, directory, manifest, resources, false);
    }
}
