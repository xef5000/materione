package dev.materione.core.pipeline;

import dev.materione.api.NamespacedId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The asset index of one pack: which {@code assets/} files exist (as ids relative to their kind directory, the
 * vanilla convention) and the pixel size of each painting texture. Parse builds it for every pack it reads, and a
 * transaction's dry run builds it for the pack it stages, so a reference to an asset resolves the same way in
 * both (D6: one pipeline, one rule).
 */
public final class PackAssets {

    /** Asset kinds whose files are referenced relative to the kind dir (vanilla convention). */
    private static final List<String> ASSET_KIND_DIRS = List.of("textures", "models", "fonts", "sounds");

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PackAssets() {}

    /**
     * Adds the pack's assets to {@code assets} and its painting-texture sizes to {@code paintingSizes}. An absent
     * {@code assets/} directory adds nothing.
     *
     * @throws IOException when the directory cannot be walked
     */
    public static void index(
            Path packDir, String packId, Set<NamespacedId> assets, Map<NamespacedId, int[]> paintingSizes)
            throws IOException {
        Path root = packDir.resolve("assets");
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                Path relativePath = root.relativize(file);
                // model/texture ids are relative to their kind directory
                // (models/ruby_sword_model.json -> <pack>:ruby_sword_model)
                Path base = relativePath;
                if (relativePath.getNameCount() > 1) {
                    String first = relativePath.getName(0).toString();
                    if (ASSET_KIND_DIRS.contains(first)) {
                        base = relativePath.subpath(1, relativePath.getNameCount());
                    }
                }
                String relative = base.toString().replace('\\', '/');
                int dot = relative.lastIndexOf('.');
                String extension = dot > 0 ? relative.substring(dot + 1) : "";
                if (dot > 0) {
                    relative = relative.substring(0, dot);
                }
                try {
                    assets.add(NamespacedId.of(packId, relative));
                } catch (IllegalArgumentException ignored) {
                    // asset names outside the id grammar surface as unresolved
                    // references at validation, not as index failures here
                }
                // painting textures carry their pixel size to the validator -
                // read here (the IO layer) so validation stays pure (R6)
                if ("png".equals(extension) && relative.startsWith("painting/")) {
                    int[] size = pngSize(file);
                    if (size != null) {
                        try {
                            paintingSizes.put(NamespacedId.parse(packId + ":" + relative), size);
                        } catch (IllegalArgumentException ignored) {
                            // the malformed name already surfaces as an unresolved reference
                        }
                    }
                }
            });
        }
    }

    /**
     * The pixel size of a PNG, straight from its IHDR header (bytes 16..24):
     * big-endian width then height. Null when the file is not a readable
     * PNG - validation then skips the size check rather than guessing.
     */
    private static int[] pngSize(Path file) {
        byte[] header = new byte[24];
        int read = 0;
        try (var in = Files.newInputStream(file)) {
            while (read < header.length) {
                int n = in.read(header, read, header.length - read);
                if (n < 0) {
                    return null;
                }
                read += n;
            }
        } catch (IOException e) {
            return null;
        }
        for (int i = 0; i < 8; i++) {
            if (header[i] != PNG_SIGNATURE[i]) {
                return null;
            }
        }
        int width = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                | ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
        int height = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                | ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);
        return new int[] {width, height};
    }
}
