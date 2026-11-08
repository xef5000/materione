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
     * How the four outer corners of a raised frame are cut (gui.md section 11.6). {@code SQUARE} is the 1-px
     * transparent corner of the shared bevel, the look every panel had before #278. {@code VANILLA} is the owner's
     * chest corner, pixel for pixel (see {@link #vanillaPart}). Absent from a file means {@code SQUARE}.
     */
    public enum Corners {
        SQUARE,
        VANILLA
    }

    /** The part a pixel of a raised frame belongs to; {@code SKIP} is not drawn (transparent). */
    enum Part {
        SKIP,
        OUTLINE,
        HIGHLIGHT,
        SHADOW,
        FILL
    }

    /**
     * A raised control face of {@code width × height} at {@code (x, y)}, with square (1-px cut) corners.
     *
     * @param edge the highlight/shadow thickness: 1 for a button, 2 for a panel
     * @param outline ARGB of the outline ring
     * @param highlight ARGB of the top and left edges
     * @param shadow ARGB of the bottom and right edges
     * @param fill ARGB of the interior and the off-diagonal corner blocks
     */
    public static void raised(RasterCanvas canvas, int x, int y, int width, int height, int edge,
            int outline, int highlight, int shadow, int fill) {
        raised(canvas, x, y, width, height, edge, Corners.SQUARE, outline, highlight, shadow, fill);
    }

    /**
     * A raised frame with the given corner style. {@link Corners#VANILLA} is defined for the 2-px panel edge; it
     * is exact for frames at least 8 × 8 (the corner rules of {@link #vanillaPart} do not overlap there).
     */
    public static void raised(RasterCanvas canvas, int x, int y, int width, int height, int edge, Corners corners,
            int outline, int highlight, int shadow, int fill) {
        if (width <= 0 || height <= 0) {
            return;
        }
        for (int ly = 0; ly < height; ly++) {
            for (int lx = 0; lx < width; lx++) {
                Part part = raisedPart(lx, ly, width, height, edge);
                if (corners == Corners.VANILLA) {
                    part = vanillaPart(lx, ly, width, height, part);
                }
                if (part == Part.SKIP) {
                    continue;
                }
                canvas.blend(x + lx, y + ly, switch (part) {
                    case OUTLINE -> outline;
                    case HIGHLIGHT -> highlight;
                    case SHADOW -> shadow;
                    default -> fill;
                });
            }
        }
    }

    /**
     * The part of pixel {@code (lx, ly)} of a raised frame with the shared rule (ring, then the edge bands).
     * Corners of the ring are {@code SKIP}; the rest of the ring is {@code OUTLINE}.
     */
    static Part raisedPart(int lx, int ly, int width, int height, int edge) {
        boolean onRing = lx == 0 || lx == width - 1 || ly == 0 || ly == height - 1;
        if (onRing) {
            boolean corner = (lx == 0 || lx == width - 1) && (ly == 0 || ly == height - 1);
            return corner ? Part.SKIP : Part.OUTLINE;
        }
        int ix = lx - 1;
        int iy = ly - 1;
        int iw = width - 2;
        int ih = height - 2;
        boolean light = iy < edge || ix < edge;
        boolean dark = iy >= ih - edge || ix >= iw - edge;
        if (light == dark) {
            return Part.FILL;
        }
        return light ? Part.HIGHLIGHT : Part.SHADOW;
    }

    /**
     * The vanilla corners (the chest's, {@code webui/static/vanilla/generic_54.png}), applied over the shared part of
     * a 2-px raised frame. Top-left, offsets from the top-left corner: {@code x + y ≤ 1} is skipped, {@code x + y = 2}
     * is outline, and {@code (3, 3)} is highlight. Top-right, offsets {@code dx = w − 1 − x} from the right edge and
     * {@code y} from the top: {@code dx + y ≤ 2} is skipped, {@code dx + y = 3} is outline (the chest's right cut is one
     * pixel larger than its left one). Bottom corners are plain outline: the chest's bottom edge is the inventory
     * separator, not a bevel. Each rule is applied in that order, so a later one wins where two overlap (frames
     * smaller than 8 × 8).
     */
    static Part vanillaPart(int lx, int ly, int width, int height, Part base) {
        Part part = base;
        if (lx + ly <= 1) {
            part = Part.SKIP;
        } else if (lx + ly == 2) {
            part = Part.OUTLINE;
        } else if (lx == 3 && ly == 3) {
            part = Part.HIGHLIGHT;
        }
        int dx = width - 1 - lx;
        if (dx + ly <= 2) {
            part = Part.SKIP;
        } else if (dx + ly == 3) {
            part = Part.OUTLINE;
        }
        if (ly == height - 1 && (lx == 0 || lx == width - 1)) {
            part = Part.OUTLINE;
        }
        return part;
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
