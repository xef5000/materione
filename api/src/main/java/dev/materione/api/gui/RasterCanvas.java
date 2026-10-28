package dev.materione.api.gui;

/**
 * The pixel sink a {@link RenderTarget#RASTER} layer paints into (gui.md §4).
 * Coordinates are integer GUI pixels with the canvas origin at the top-left;
 * colours are ARGB ints with straight alpha. Writes outside the canvas are
 * clipped, never an error. No Bukkit or pack types: the flattening, the PNG
 * encoding and the shared bevel vectors live in the content module, so a
 * component can be tested headless.
 */
public interface RasterCanvas {

    /** Canvas width in GUI pixels (176 for a chest). */
    int width();

    /** Canvas height in GUI pixels: {@code 114 + rows * 18}. */
    int height();

    /** Source-over composites one pixel. */
    void blend(int x, int y, int argb);

    /** Source-over composites a {@code width × height} block of pixels, row-major. */
    void blend(int x, int y, int width, int height, int[] argb);

    /** Replaces a rectangle with one colour (no blending). */
    void fill(int x, int y, int width, int height, int argb);
}
