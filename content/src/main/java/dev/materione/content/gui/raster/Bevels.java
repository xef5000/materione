package dev.materione.content.gui.raster;

import dev.materione.api.gui.RasterCanvas;

/**
 * The Minecraft-style bevel shared by {@code panel}, {@code button} and {@code slots}
 * (gui.md §4; the rules and their vectors are in {@code fixtures/gui/raster/README.md}).
 * A panel is a large button and a slot well is an inset button, so one routine paints all
 * three. Every pixel is classified from its offset inside its rectangle, so the parts
 * partition the rectangle: each pixel is written at most once and small rectangles need no
 * special case.
 *
 * <p><strong>Raised</strong> (panel with {@code edge = 2}, button with {@code edge = 1}).
 * A ring pixel (on the rectangle's border) is outline, except the four corners, which are
 * not drawn (transparent). Inside the ring, with inner offsets {@code ix, iy} and inner size
 * {@code iw × ih}: {@code light} when {@code iy < edge} or {@code ix < edge}; {@code dark}
 * when {@code iy ≥ ih − edge} or {@code ix ≥ iw − edge}. Both (the top-right and bottom-left
 * corner blocks) and neither (the interior) are fill; light only is highlight; dark only is
 * shadow.
 *
 * <p><strong>Inset</strong> (slot wells, no outline). For offsets {@code lx, ly} in the
 * rectangle itself: {@code dark} when {@code ly == 0} or {@code lx == 0}, {@code light} when
 * {@code ly == h − 1} or {@code lx == w − 1}. Both or neither is fill, dark only is shadow,
 * light only is highlight.
 *
 * <p>Painting is source-over, so a transparent colour or a skipped corner shows what lies
 * below.
 */
public final class Bevels {

    private Bevels() {}

    /**
     * A raised control face of {@code width × height} at {@code (x, y)}.
     *
     * @param edge the highlight/shadow thickness: 1 for a button, 2 for a panel
     * @param outline ARGB of the outline ring
     * @param highlight ARGB of the top and left edges
     * @param shadow ARGB of the bottom and right edges
     * @param fill ARGB of the interior and the off-diagonal corner blocks
     */
    public static void raised(RasterCanvas canvas, int x, int y, int width, int height, int edge,
            int outline, int highlight, int shadow, int fill) {
        if (width <= 0 || height <= 0) {
            return;
        }
        for (int ly = 0; ly < height; ly++) {
            for (int lx = 0; lx < width; lx++) {
                boolean onRing = lx == 0 || lx == width - 1 || ly == 0 || ly == height - 1;
                if (!onRing) {
                    int ix = lx - 1;
                    int iy = ly - 1;
                    int iw = width - 2;
                    int ih = height - 2;
                    boolean light = iy < edge || ix < edge;
                    boolean dark = iy >= ih - edge || ix >= iw - edge;
                    canvas.blend(x + lx, y + ly, pick(light, dark, highlight, shadow, fill));
                } else {
                    boolean corner = (lx == 0 || lx == width - 1) && (ly == 0 || ly == height - 1);
                    if (!corner) {
                        canvas.blend(x + lx, y + ly, outline);
                    }
                }
            }
        }
    }

    /**
     * An inset slot well of {@code width × height} at {@code (x, y)}: no outline, shadow on
     * the top and left, highlight on the bottom and right, interior and off-diagonal corners
     * in fill.
     */
    public static void inset(RasterCanvas canvas, int x, int y, int width, int height,
            int highlight, int shadow, int fill) {
        if (width <= 0 || height <= 0) {
            return;
        }
        for (int ly = 0; ly < height; ly++) {
            for (int lx = 0; lx < width; lx++) {
                boolean dark = ly == 0 || lx == 0;
                boolean light = ly == height - 1 || lx == width - 1;
                canvas.blend(x + lx, y + ly, pick(dark, light, shadow, highlight, fill));
            }
        }
    }

    /**
     * The colour of a pixel from its two edge flags: both or neither is fill, the first flag
     * alone is {@code firstOnly}, the second alone is {@code secondOnly}.
     */
    private static int pick(boolean first, boolean second, int firstOnly, int secondOnly, int fill) {
        if (first == second) {
            return fill;
        }
        return first ? firstOnly : secondOnly;
    }
}
