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
 *
 * <p><strong>Origin.</strong> A canvas covers the rectangle {@code [originX, originX + width) ×
 * [originY, originY + height)} of GUI pixels, and every coordinate on its interface is a GUI pixel, so a
 * layer paints at its authored position whatever the rectangle is. The screen canvases of the shared
 * vectors have origin {@code (0, 0)}; the open canvas of gui.md section 11 starts at the art cap's
 * top-left corner, which is negative. The canvas also remembers the smallest rectangle it wrote to, so
 * clearing, compositing and finding the painted bounds visit only that rectangle, and whether a visible
 * pixel was ever clipped by its edge.
 */
public final class ArgbCanvas implements RasterCanvas {

    private final int originX;
    private final int originY;
    private final int width;
    private final int height;
    private final int[] pixels;

    // the written rectangle in array coordinates, right and bottom exclusive; empty when right <= left
    private int dirtyLeft = Integer.MAX_VALUE;
    private int dirtyTop = Integer.MAX_VALUE;
    private int dirtyRight = Integer.MIN_VALUE;
    private int dirtyBottom = Integer.MIN_VALUE;
    private boolean clippedInk;

    /** The smallest box holding every pixel with alpha above zero: GUI pixels, origin at its top-left. */
    public record Bounds(int x, int y, int width, int height) {}

    /** A transparent canvas (every pixel {@code 0x00000000}) whose top-left is the GUI pixel {@code (0, 0)}. */
    public ArgbCanvas(int width, int height) {
        this(0, 0, width, height);
    }

    /** A transparent canvas whose top-left is the GUI pixel {@code (originX, originY)}. */
    public ArgbCanvas(int originX, int originY, int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("canvas must be at least 1x1: " + width + "x" + height);
        }
        this.originX = originX;
        this.originY = originY;
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    /** The GUI x of the canvas's left column. */
    public int originX() {
        return originX;
    }

    /** The GUI y of the canvas's top row. */
    public int originY() {
        return originY;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    /** The pixel at GUI pixel {@code (x, y)}; {@code 0} outside the canvas. */
    public int argb(int x, int y) {
        return inside(x, y) ? pixels[index(x, y)] : 0;
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
            if ((argb >>> 24) != 0) {
                markDirty(x - originX, y - originY, x - originX + 1, y - originY + 1);
            }
            int index = index(x, y);
            pixels[index] = over(argb, pixels[index]);
        } else if ((argb >>> 24) != 0) {
            clippedInk = true;
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
        int left = Math.max(0, x - originX);
        int top = Math.max(0, y - originY);
        int right = Math.min(width, x - originX + rectWidth);
        int bottom = Math.min(height, y - originY + rectHeight);
        if (rectWidth > 0 && rectHeight > 0 && (argb >>> 24) != 0
                && (x < originX || y < originY
                        || x - originX + rectWidth > width || y - originY + rectHeight > height)) {
            clippedInk = true;
        }
        if (left >= right || top >= bottom) {
            return;
        }
        markDirty(left, top, right, bottom);
        for (int row = top; row < bottom; row++) {
            Arrays.fill(pixels, row * width + left, row * width + right, argb);
        }
    }

    /**
     * Source-over composites {@code layer} onto this canvas with the layer's opacity
     * ({@code 0..100}). Both canvases must cover the same rectangle. Only the rectangle the layer
     * wrote to is visited: every other pixel has alpha 0 and leaves the destination unchanged.
     */
    public void composite(ArgbCanvas layer, int opacity) {
        if (layer.width != width || layer.height != height || layer.originX != originX
                || layer.originY != originY) {
            throw new IllegalArgumentException("layer is " + layer.width + "x" + layer.height + " at ("
                    + layer.originX + ", " + layer.originY + "), canvas is " + width + "x" + height + " at ("
                    + originX + ", " + originY + ")");
        }
        if (layer.dirtyRight <= layer.dirtyLeft) {
            return;
        }
        markDirty(layer.dirtyLeft, layer.dirtyTop, layer.dirtyRight, layer.dirtyBottom);
        for (int row = layer.dirtyTop; row < layer.dirtyBottom; row++) {
            for (int column = layer.dirtyLeft; column < layer.dirtyRight; column++) {
                int index = row * width + column;
                int source = layer.pixels[index];
                if (opacity != 100) {
                    int alpha = (source >>> 24) * opacity;
                    source = (rdiv(alpha, 100) << 24) | (source & 0x00FFFFFF);
                }
                pixels[index] = over(source, pixels[index]);
            }
        }
    }

    /** Makes the canvas transparent again and forgets what it clipped, so one buffer serves many layers. */
    public void clear() {
        if (dirtyRight > dirtyLeft) {
            for (int row = dirtyTop; row < dirtyBottom; row++) {
                Arrays.fill(pixels, row * width + dirtyLeft, row * width + dirtyRight, 0);
            }
        }
        dirtyLeft = Integer.MAX_VALUE;
        dirtyTop = Integer.MAX_VALUE;
        dirtyRight = Integer.MIN_VALUE;
        dirtyBottom = Integer.MIN_VALUE;
        clippedInk = false;
    }

    /**
     * Whether a write with alpha above zero fell outside this canvas since it was created or last
     * {@link #clear cleared}: the part of a layer past the art cap, which the cap drops.
     */
    public boolean clippedInk() {
        return clippedInk;
    }

    /** The smallest box holding every pixel with alpha above zero, or {@code null} when there is none. */
    public Bounds paintedBounds() {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        if (dirtyRight > dirtyLeft) {
            for (int row = dirtyTop; row < dirtyBottom; row++) {
                for (int column = dirtyLeft; column < dirtyRight; column++) {
                    if ((pixels[row * width + column] >>> 24) != 0) {
                        left = Math.min(left, column);
                        right = Math.max(right, column + 1);
                        top = Math.min(top, row);
                        bottom = Math.max(bottom, row + 1);
                    }
                }
            }
        }
        return left == Integer.MAX_VALUE ? null
                : new Bounds(originX + left, originY + top, right - left, bottom - top);
    }

    /** A copy of the {@code w × h} block whose top-left is the GUI pixel {@code (x, y)}, row-major; outside is 0. */
    public int[] crop(int x, int y, int w, int h) {
        int[] out = new int[w * h];
        for (int row = 0; row < h; row++) {
            for (int column = 0; column < w; column++) {
                out[row * w + column] = argb(x + column, y + row);
            }
        }
        return out;
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
        return x >= originX && y >= originY && x - originX < width && y - originY < height;
    }

    private int index(int x, int y) {
        return (y - originY) * width + (x - originX);
    }

    private void markDirty(int left, int top, int right, int bottom) {
        dirtyLeft = Math.min(dirtyLeft, left);
        dirtyTop = Math.min(dirtyTop, top);
        dirtyRight = Math.max(dirtyRight, right);
        dirtyBottom = Math.max(dirtyBottom, bottom);
    }
}
