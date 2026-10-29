package dev.materione.content.gui;

import dev.materione.api.gui.TextureSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The pixels of a draft: the draft's own PNGs where it carries one, the saved pack's otherwise.
 * Keys are the texture references the compiler asks for ({@code ns:path}, the pack's namespace).
 * Read-only: a draft never reaches the disk.
 */
final class DraftTextures implements TextureSource {

    private final TextureSource saved;
    private final String namespace;
    private final Map<String, TextureSource.Pixels> drafts;

    /**
     * @param saved the pack's own textures
     * @param namespace the pack id, the namespace of the draft's references
     * @param drafts decoded draft PNGs by pack-relative reference ({@code gui/shop/main/art})
     */
    DraftTextures(TextureSource saved, String namespace, Map<String, TextureSource.Pixels> drafts) {
        this.saved = saved;
        this.namespace = namespace;
        this.drafts = new HashMap<>(drafts);
    }

    @Override
    public Optional<Pixels> texture(String texture) {
        if (texture != null && texture.startsWith(namespace + ":")) {
            Pixels draft = drafts.get(texture.substring(namespace.length() + 1));
            if (draft != null) {
                return Optional.of(draft);
            }
        }
        return saved.texture(texture);
    }
}
