package dev.materione.core.pipeline;

import dev.materione.core.document.LosslessYamlDocument;

/**
 * A parsed {@code pack.yml} (master §7.2): id, version, schema version,
 * depends, credits. Carries its document so the manifest round-trips
 * losslessly like any other document (§5.6).
 */
public record PackManifest(
        String id,
        String version,
        int schemaVersion,
        java.util.List<String> depends,
        LosslessYamlDocument document) {

    public PackManifest {
        depends = java.util.List.copyOf(depends);
    }

    /** Current manifest schema version; manifests migrate forward to this (master §5.5). */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Extracts a manifest from a parsed {@code pack.yml}. Returns null when
     * the file is structurally invalid — the caller quarantines the pack
     * with MTR-0013 instead of guessing defaults.
     */
    public static PackManifest fromDocument(LosslessYamlDocument document) {
        if (document.parseError().isPresent()) {
            return null;
        }
        var data = document.data();
        Object id = data.get("id");
        if (!(id instanceof String packId) || packId.isBlank()) {
            return null;
        }
        Object version = data.getOrDefault("version", "0");
        Object schema = data.getOrDefault("schema_version", CURRENT_SCHEMA_VERSION);
        if (!(schema instanceof Number schemaNumber)) {
            return null;
        }
        var depends = new java.util.ArrayList<String>();
        Object rawDepends = data.get("depends");
        if (rawDepends instanceof java.util.List<?> list) {
            for (Object entry : list) {
                if (entry instanceof String s) {
                    depends.add(s);
                }
            }
        }
        return new PackManifest(
                packId,
                String.valueOf(version),
                schemaNumber.intValue(),
                depends,
                document);
    }
}
