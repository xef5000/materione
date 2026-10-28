package dev.materione.core.texture;

import dev.materione.api.gui.TextureSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The textures of one content pack, read from its own {@code assets/textures/}
 * tree (the same tree {@code PackCompiler} copies into {@code assets/<pack>/}).
 * A reference names its namespace: only the pack's own id resolves here, so a
 * GUI cannot read another pack's files. Lookups are cached per instance, which
 * lives for one compile or one shadow build.
 */
public final class PackTextures implements TextureSource {

    /** A texture path under the pack's textures/: segments of safe characters, no traversal. */
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_./-]*");

    private final String packId;
    private final Path textures;
    private final Map<String, Optional<Pixels>> cache = new HashMap<>();

    /**
     * @param packId the pack's id, which is its namespace
     * @param packDirectory the pack folder ({@code content/packs/<pack>})
     */
    public PackTextures(String packId, Path packDirectory) {
        this.packId = packId;
        this.textures = packDirectory.resolve("assets").resolve("textures");
    }

    @Override
    public synchronized Optional<Pixels> texture(String texture) {
        if (texture == null) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(texture, this::read);
    }

    private Optional<Pixels> read(String reference) {
        String path = reference;
        int colon = reference.indexOf(':');
        if (colon >= 0) {
            if (!reference.substring(0, colon).equals(packId)) {
                return Optional.empty();
            }
            path = reference.substring(colon + 1);
        }
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - ".png".length());
        }
        if (!PATH.matcher(path).matches() || path.contains("..")) {
            return Optional.empty();
        }
        Path file = textures.resolve(path + ".png");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return PngDecoder.decode(Files.readAllBytes(file));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }
}
