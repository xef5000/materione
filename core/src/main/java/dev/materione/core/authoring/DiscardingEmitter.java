package dev.materione.core.authoring;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.gui.TextureSource;
import dev.materione.core.texture.PackTextures;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A {@link PackEmitter} for the dry run of an authoring transaction: the compile runs for its diagnostics, and
 * every file it would emit is dropped. It writes nothing and keeps nothing; the problems the compile raises
 * ({@link #diagnostic}) are the only output. Textures are read from the pack on disk, as the real compile does.
 */
final class DiscardingEmitter implements PackEmitter {

    private final TextureSource textures;
    private final List<Diagnostic> diagnostics;

    /**
     * @param pack the pack's id, which is its namespace
     * @param packDirectory the pack folder, for reading textures
     * @param diagnostics where the compile's non-fatal problems are appended, in the order they are raised
     */
    DiscardingEmitter(String pack, Path packDirectory, List<Diagnostic> diagnostics) {
        this.textures = new PackTextures(pack, packDirectory);
        this.diagnostics = diagnostics;
    }

    @Override
    public TextureSource textures() {
        return textures;
    }

    @Override
    public void diagnostic(Diagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    @Override
    public void itemDefinition(NamespacedId id, Map<String, Object> itemModelJson) {
        // dropped: a dry run emits no file
    }

    @Override
    public void model(NamespacedId id, Map<String, Object> modelJson) {
        // dropped
    }

    @Override
    public void copyAsset(Path sourceFile, String destination) {
        // dropped
    }

    @Override
    public void vanillaFile(String path, Map<String, Object> json) {
        // dropped
    }

    @Override
    public void description(String text) {
        // dropped
    }
}
