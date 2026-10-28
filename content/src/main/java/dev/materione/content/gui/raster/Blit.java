package dev.materione.content.gui.raster;

import dev.materione.api.gui.RasterCanvas;
import dev.materione.api.gui.TextureSource;

/**
 * Nearest-neighbour texture placement (gui.md §4). Each destination pixel centre
 * {@code (i + ½, j + ½)} samples the source pixel that contains its scaled centre:
 * {@code sx = ⌊(2i + 1)·sw / (2·dw)⌋}, and likewise for rows. The result is integer
 * math throughout, so a scaled image is the same pixels in the TypeScript mirror.
 */
public final class Blit {

    private Blit() {}

    /**
     * Draws {@code source} into the {@code width × height} rectangle at {@code (x, y)},
     * source-over. The source's own size is the scale reference, so the same call places
     * it at natural size ({@code width == source.width()}) or stretched.
     */
    public static void draw(RasterCanvas canvas, int x, int y, int width, int height,
            TextureSource.Pixels source) {
        int sourceWidth = source.width();
        int sourceHeight = source.height();
        int[] pixels = source.argb();
        for (int row = 0; row < height; row++) {
            int sy = Math.min(sourceHeight - 1, (2 * row + 1) * sourceHeight / (2 * height));
            for (int column = 0; column < width; column++) {
                int sx = Math.min(sourceWidth - 1, (2 * column + 1) * sourceWidth / (2 * width));
                canvas.blend(x + column, y + row, pixels[sy * sourceWidth + sx]);
            }
        }
    }
}
