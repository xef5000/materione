package dev.materione.content.paintings;

import dev.materione.api.NamespacedId;
import java.util.List;
import java.util.Map;

/**
 * A parsed custom painting (runtime-systems §10): one 1.21+ painting variant
 * — texture, block size, metadata. The variant rides the generated world
 * datapack on legacy servers (modern servers register it via Paper's
 * registry events); the texture ships in the resource pack under
 * {@code assets/<ns>/textures/painting/} and must measure exactly
 * {@code width*16 × height*16} pixels (validated against the header the
 * parse stage recorded). {@code assetId} is {@code <ns>:<path>} without the
 * {@code painting/} segment — the client's paintings atlas sprite id.
 */
public record PaintingDefinition(
        NamespacedId id,
        String assetId,
        int width,
        int height,
        String author,
        String title,
        boolean placeable) {

    /** Expected texture width in pixels. */
    public int textureWidth() {
        return width * 16;
    }

    /** Expected texture height in pixels. */
    public int textureHeight() {
        return height * 16;
    }
}
