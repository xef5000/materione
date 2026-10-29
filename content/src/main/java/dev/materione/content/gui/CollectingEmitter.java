package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.gui.TextureSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory {@link PackEmitter} for the GUI compile: it keeps what the compile emits (the
 * diagnostics in the order they are raised, the generated assets and the font providers) and
 * writes nothing. A GUI's compile emits nothing else, so the other hooks refuse loudly rather
 * than drop output silently.
 */
final class CollectingEmitter implements PackEmitter {

    private final TextureSource textures;
    final List<Diagnostic> diagnostics;
    final Map<String, byte[]> assets = new LinkedHashMap<>();
    final List<Map.Entry<String, Map<String, Object>>> fonts = new ArrayList<>();

    /**
     * @param textures the pixels a painting layer reads
     * @param diagnostics the list the compile's diagnostics are appended to, in raised order
     */
    CollectingEmitter(TextureSource textures, List<Diagnostic> diagnostics) {
        this.textures = textures;
        this.diagnostics = diagnostics;
    }

    @Override
    public TextureSource textures() {
        return textures;
    }

    @Override
    public void generatedAsset(String destination, byte[] bytes) {
        assets.put(destination, bytes.clone());
    }

    @Override
    public void diagnostic(Diagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    @Override
    public void fontProvider(String fontKey, Map<String, Object> providerJson, FontMembership membership) {
        fonts.add(Map.entry(fontKey, providerJson));
    }

    @Override
    public void itemDefinition(NamespacedId id, Map<String, Object> itemModelJson) {
        throw new UnsupportedOperationException("a GUI compile emits no item definitions");
    }

    @Override
    public void model(NamespacedId id, Map<String, Object> modelJson) {
        throw new UnsupportedOperationException("a GUI compile emits no models");
    }

    @Override
    public void copyAsset(Path sourceFile, String destination) {
        throw new UnsupportedOperationException("a GUI compile copies no assets");
    }

    @Override
    public void vanillaFile(String path, Map<String, Object> json) {
        throw new UnsupportedOperationException("a GUI compile writes no vanilla files");
    }

    @Override
    public void description(String text) {
        throw new UnsupportedOperationException("a GUI compile writes no pack description");
    }
}
