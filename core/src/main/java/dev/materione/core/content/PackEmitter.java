package dev.materione.core.content;

import java.nio.file.Path;
import java.util.Map;

/**
 * The compiler-facing output surface handed to content types (plugin
 * architecture §4, compile stage). JSON-shaped payloads ({@code Map/List/
 * String/Number/Boolean/null}) keep the contract free of any serializer —
 * the pack module owns how bytes are produced (creative, M1).
 */
public interface PackEmitter {

    /** Emits {@code assets/<ns>/items/<id>.json} (1.21.4+ item definition). */
    void itemDefinition(dev.materione.api.NamespacedId id, Map<String, Object> itemModelJson);

    /**
     * Emits {@code assets/<ns>/items/<id>.json} on behalf of a block or
     * furniture's auto-generated placement item (the definition declares no
     * {@code item:} link). Collisions resolve toward real content: when an
     * actual item resource owns the id, its own definition wins and the
     * synthetic one is dropped.
     */
    default void syntheticItemDefinition(dev.materione.api.NamespacedId id, Map<String, Object> itemModelJson) {
        // default no-op so addon content types compiled against older cores link
    }

    /** Emits {@code assets/<ns>/models/<id>.json}. */
    void model(dev.materione.api.NamespacedId id, Map<String, Object> modelJson);

    /** Copies a pack asset file verbatim ({@code assets/<kind>/<path>}). */
    void copyAsset(Path sourceFile, String destination);

    /**
     * Emits (merges into) {@code assets/minecraft/<path>} — the override seam
     * for vanilla-namespace files the client merges by key ({@code
     * blockstates/*.json}, {@code sounds.json}). Fragments from every pack
     * deep-merge deterministically; conflicting values keep the first and are
     * diagnosed. Vanilla-namespace files that must not merge ship through
     * {@link #copyAsset} instead.
     */
    void vanillaFile(String path, Map<String, Object> json);

    /**
     * Emits one font provider into the named font key ({@code ns:name}) —
     * the glyph system's output. Providers from all packs merge into one
     * {@code assets/<ns>/font/<name>.json} deterministically (compile order
     * within a font, sorted font keys across the pack).
     */
    default void fontProvider(String fontKey, Map<String, Object> providerJson) {
        // default no-op so addon content types compiled against older cores link
    }

    /** Sets pack.mcmeta description text (pack format is the platform's). */
    void description(String text);
}
