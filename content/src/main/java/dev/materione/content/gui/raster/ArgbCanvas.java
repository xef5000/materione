package dev.materione.content.gui.raster;

import dev.materione.api.gui.RasterCanvas;
import java.util.Arrays;

/**
 * The raster surface GUI layers paint into (gui.md §4), backed by an ARGB int array.
 *
 * <p>Pixel rules (the exact integer math the TypeScript mirror and the shared vectors
 * in {@code fixtures/gui/raster/} must reproduce):
 * <ul>
 *   <li>Straight alpha. A source pixel with alpha {@code sa} over a destination with
 *       alpha {@code da} yields {@code den = 255·sa + da·(255 − sa)},
 *       {@code oa = rdiv(den, 255)} and, per colour channel,
 *       {@code c = min(255, rdiv(sc·sa·255 + dc·da·(255 − sa), den))}.</li>
 *   <li>{@code rdiv(n, d) = (n + d/2) / d}: integer division rounding half up (n, d ≥ 0).</li>
 *   <li>Source alpha 0 leaves the destination unchanged; source alpha 255 replaces it.</li>
 *   <li>Layer opacity {@code o} (0–100) scales the source alpha: {@code sa' = rdiv(sa·o, 100)}.</li>
 *   <li>Writes outside the canvas are clipped, never an error.</li>
 * </ul>
 */
public final class ArgbCanvas implements RasterCanvas {

    private final int width;
    private final int height;
    private final int[] pixels;

    /** A transparent canvas (every pixel {@code 0x00000000}). */
    public ArgbCanvas(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("canvas must be at least 1x1: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    /** The pixel at {@code (x, y)}; {@code 0} outside the canvas. */
    public int argb(int x, int y) {
        return inside(x, y) ? pixels[y * width + x] : 0;
    }

    /** A copy of the row-major pixels. */
    public int[] toArray() {
        return pixels.clone();
    }

    /**
     * The client's ink width of this canvas as a glyph: the index of the rightmost column
     * holding any pixel with alpha ≠ 0, plus one; {@code 0} when the canvas is empty. The
     * client's glyph advance is this value plus one (gui.md §4, step 4).
     */
    public int inkWidth() {
        for (int column = width - 1; column >= 0; column--) {
            for (int row = 0; row < height; row++) {
                if ((pixels[row * width + column] >>> 24) != 0) {
                    return column + 1;
                }
            }
        }
        return 0;
    }

    @Override
    public void blend(int x, int y, int argb) {
        if (inside(x, y)) {
            int index = y * width + x;
            pixels[index] = over(argb, pixels[index]);
        }
    }

    @Override
    public void blend(int x, int y, int blockWidth, int blockHeight, int[] argb) {
        for (int row = 0; row < blockHeight; row++) {
            for (int column = 0; column < blockWidth; column++) {
                blend(x + column, y + row, argb[row * blockWidth + column]);
            }
        }
    }

    @Override
    public void fill(int x, int y, int rectWidth, int rectHeight, int argb) {
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(width, x + rectWidth);
        int bottom = Math.min(height, y + rectHeight);
        for (int row = top; row < bottom; row++) {
            Arrays.fill(pixels, row * width + left, row * width + right, argb);
        }
    }

    /**
     * Source-over composites {@code layer} onto this canvas with the layer's opacity
     * ({@code 0..100}). Both canvases must have the same size.
     */
    public void composite(ArgbCanvas layer, int opacity) {
        if (layer.width != width || layer.height != height) {
            throw new IllegalArgumentException("layer is " + layer.width + "x" + layer.height
                    + ", canvas is " + width + "x" + height);
        }
        for (int index = 0; index < pixels.length; index++) {
            int source = layer.pixels[index];
            if (opacity != 100) {
                int alpha = (source >>> 24) * opacity;
                source = (rdiv(alpha, 100) << 24) | (source & 0x00FFFFFF);
            }
            pixels[index] = over(source, pixels[index]);
        }
    }

    /**
     * The source-over rule of the class comment, for one pixel, written as the shared
     * vector README states it: a fully opaque or fully transparent destination takes the
     * source exactly; otherwise {@code outA = floor((den + 127) / 255)} and each channel is
     * {@code floor((2·(sc·sa·255 + dc·da·keep) + den) / (2·den))}.
     */
    static int over(int source, int destination) {
        int sa = source >>> 24;
        if (sa == 0) {
            return destination; // a transparent source writes nothing
        }
        int da = destination >>> 24;
        if (sa == 255 || da == 0) {
            return source;
        }
        int keep = 255 - sa;
        int den = sa * 255 + da * keep;
        int alpha = (den + 127) / 255;
        int red = channel(source >>> 16, destination >>> 16, sa, da, keep, den);
        int green = channel(source >>> 8, destination >>> 8, sa, da, keep, den);
        int blue = channel(source, destination, sa, da, keep, den);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int channel(int sourceShifted, int destinationShifted, int sa, int da, int keep, int den) {
        int sc = sourceShifted & 0xFF;
        int dc = destinationShifted & 0xFF;
        return (2 * (sc * sa * 255 + dc * da * keep) + den) / (2 * den);
    }

    /** {@code n / d} rounded half up, for non-negative {@code n} and positive {@code d}. */
    static int rdiv(int n, int d) {
        return (n + d / 2) / d;
    }

    private boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }
}
