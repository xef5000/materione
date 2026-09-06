package dev.materione.content.glyphs;

import dev.materione.api.NamespacedId;
import java.util.List;

/**
 * A parsed glyph (runtime-systems §6): one bitmap entry of a pack font — a
 * chat emoji, an interface icon, a sprite-sheet slice or an animation's
 * frames. Texture and font references are resolved (namespace applied) at
 * parse so compile and the runtime text pipeline consume one shape.
 *
 * <p>Codepoints are never authored: the compiler allocates a durable
 * consecutive run (D7) and both the pack JSON and the runtime registry read
 * the same allocation. {@code char} is deliberately not a field — unlike
 * Nexo/Oraxen there is no write-back into user YAML and no collision class.
 */
public record GlyphDefinition(
        NamespacedId id,
        String fontKey,
        String texture,
        List<String> frames,
        int ascent,
        int height,
        int rows,
        int columns,
        String reference,
        String indexSpec,
        List<String> placeholders,
        String permission,
        boolean emoji,
        boolean tabComplete,
        String shadowColor) {

    /** Number of durable codepoints this glyph occupies (0 for pure aliases). */
    public int codepointCount() {
        if (reference != null) {
            return 0;
        }
        return !frames.isEmpty() ? frames.size() : rows * columns;
    }

    /** True when this glyph renders through another glyph's bitmaps. */
    public boolean isReference() {
        return reference != null;
    }

    /** True when the glyph cycles through multiple textures. */
    public boolean isAnimated() {
        return reference == null && !frames.isEmpty();
    }
}
