package dev.materione.api.content;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.gui.TextureSource;
import java.nio.file.Path;
import java.util.Map;

/**
 * The compiler-facing output surface handed to content types (plugin
 * architecture §4, compile stage). JSON-shaped payloads ({@code Map/List/
 * String/Number/Boolean/null}) keep the contract free of any serializer —
 * the pack module owns how bytes are produced (creative, M1).
 */
public interface PackEmitter {

    /**
     * Emits a binary file the compiler generated, at a pack-relative path under
     * {@code assets/} ({@code assets/<ns>/textures/font/…png}). Unlike
     * {@link #copyAsset} the bytes come from the content type, not from a source
     * file (GUI backgrounds, the first-party small-caps sheet). Emitting the same
     * path twice with the same bytes is harmless; different bytes keep the last.
     */
    default void generatedAsset(String destination, byte[] bytes) {
        // default no-op so addon content types compiled against older cores link
    }

    /**
     * Pixel access to the owning pack's own textures, for compile hooks that
     * composite raster output (gui.md §4). Reads only: it does not make a texture
     * an output. Parsing never uses it.
     */
    default TextureSource textures() {
        return TextureSource.NONE;
    }

    /**
     * Publishes a non-fatal problem against the resource being compiled, such as one
     * GUI layer skipped at compile time. The resource's output is still emitted.
     */
    default void diagnostic(Diagnostic diagnostic) {
        // default no-op so addon content types compiled against older cores link
    }

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
     * Whether a generated font is referenced from the client's default font
     * ({@code assets/minecraft/font/default.json}). Unstyled text (chat, item
     * names, vanilla labels) renders only through the default font, so a font
     * that joins it also takes over every plain codepoint it maps. Issue #198:
     * a GUI's ASCII text fonts must not, so the emitting content type declares
     * this on every call rather than the pack inferring it from a font name.
     */
    enum FontMembership {
        /** Referenced from {@code minecraft:default}: glyph fonts (private-use codepoints). */
        JOINS_DEFAULT,
        /** Never referenced from {@code minecraft:default}: used only by text that names its font key. */
        STYLED_ONLY
    }

    /**
     * Emits one font provider into the named font key ({@code ns:name}) and
     * declares whether that font joins the default font. Providers from all packs
     * merge into one {@code assets/<ns>/font/<name>.json} deterministically (compile
     * order within a font, sorted font keys across the pack). A font key has one
     * membership per pack: if emissions disagree, {@link FontMembership#STYLED_ONLY}
     * wins, so a disagreement never makes styled text take over unstyled text.
     */
    default void fontProvider(String fontKey, Map<String, Object> providerJson, FontMembership membership) {
        // default no-op so addon content types compiled against older cores link
    }

    /**
     * The legacy form of {@link #fontProvider(String, Map, FontMembership)} for addons
     * compiled against older cores: the glyph system's original meaning, a font that
     * joins the default font. In-repo content declares its membership explicitly.
     */
    default void fontProvider(String fontKey, Map<String, Object> providerJson) {
        fontProvider(fontKey, providerJson, FontMembership.JOINS_DEFAULT);
    }

    /**
     * Emits one sound event definition ({@code sounds.json} entry) under its
     * full key ({@code ns:event}); events group per namespace into that
     * namespace's {@code sounds.json}.
     */
    default void soundEvent(String soundKey, Map<String, Object> eventJson) {
        // default no-op so addon content types compiled against older cores link
    }

    /**
     * Emits one datapack artifact file ({@code data/<ns>/painting_variant/…},
     * jukebox songs, tags) — compiled into the world datapack, whose changes
     * are restart-required (runtime-systems §10).
     */
    default void datapackFile(String path, Map<String, Object> json) {
        // default no-op so addon content types compiled against older cores link
    }

    /**
     * Emits one compiled text effect (runtime-systems §6) — the pack
     * compiler composes the injected text-shader set from all effects and
     * keys each on its allocated signal color. Only meaningful once the
     * shader ladder ships; packs without effects never carry shader files.
     */
    default void textEffect(EffectSpec spec) {
        // default no-op so addon content types compiled against older cores link
    }

    /**
     * The JSON form of an authored text value (master §5.7: MiniMessage, legacy
     * codes accepted) for a data file the client reads as a text component,
     * such as a painting variant's title. Returns a {@code String} for text
     * without formatting, else a JSON-shaped text component. Compiling it
     * needs the plugin's text pipeline (plugin §6.1), which content types
     * cannot see; the default returns the stored string unchanged.
     */
    default Object textComponent(String stored) {
        return stored;
    }

    /** Sets pack.mcmeta description text (pack format is the platform's). */
    void description(String text);
}
