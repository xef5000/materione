package dev.materione.content.gui;

import dev.materione.api.content.CompileRejectException;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.gui.GuiContainer;
import dev.materione.content.gui.raster.ArgbCanvas;
import java.util.ArrayList;
import java.util.List;

/**
 * The open canvas of a GUI (gui.md §11): the art cap, and the cut of a flattened raster into the
 * tiles that become font providers. Pure arithmetic over an {@link ArgbCanvas}; it knows nothing of
 * layers, allocations or the title.
 *
 * <p>Symbols follow §11.3: {@code (tx, ty)} is a tile's top-left in GUI pixels, {@code tw × th} its
 * art size, {@code a} its provider's ascent and {@code H} its cell height (the PNG's height).
 */
final class GuiArt {

    /** The cap's width and height: 1024 px each. */
    static final int CAP_SIZE = 1024;

    /** A tile is cut on a grid of this many pixels, the client's glyph cell limit (§9.11). */
    static final int TILE = 256;

    private GuiArt() {}

    /**
     * The art cap of one container (gui.md §11.2, §12.5.4): a {@link #CAP_SIZE}-px box whose left column is
     * centred on the screen, {@code (width − 1024) / 2} (−424 for a 176-px screen), and whose top row is the highest
     * one the client can draw, {@code titleY + 7 − 256} (−243 for the profiles' title row 6, §9.10). Every v1
     * container shares one box; a later profile with another screen width or title row has its own.
     *
     * @param x the cap's left column
     * @param y the cap's top row
     */
    record Cap(int x, int y) {

        /** The cap of {@code container}. */
        static Cap of(GuiContainer container) {
            return new Cap((container.width() - CAP_SIZE) / 2, container.titleY() + GuiTitle.BASELINE - TILE);
        }

        /** The last column of the cap. */
        int right() {
            return x + CAP_SIZE - 1;
        }

        /** The last row of the cap. */
        int bottom() {
            return y + CAP_SIZE - 1;
        }
    }

    /**
     * One tile of art.
     *
     * @param row the tile's grid row, counted from the painted bounds' top-left
     * @param col the tile's grid column
     * @param x {@code tx}, the GUI column of its first inked column
     * @param y {@code ty}, the GUI row of its first inked row
     * @param width {@code tw}, the art width (also its ink width: the tile is cropped to its ink)
     * @param height {@code th}, the art height
     * @param ascent {@code a = 13 − ty}
     * @param cell {@code H = max(th, a)}, the provider's height and the PNG's height
     * @param argb {@code width × cell} pixels, row-major; the art is in rows {@code 0..th−1}, the rest is padding
     */
    record Tile(int row, int col, int x, int y, int width, int height, int ascent, int cell, int[] argb) {

        /** The number of transparent rows below the art: {@code H − th}, nonzero only when the tile ends above row 13. */
        int padding() {
            return cell - height;
        }
    }

    /** A canvas covering {@code container}'s art cap, ready for layers to paint at their GUI coordinates. */
    static ArgbCanvas capCanvas(GuiContainer container) {
        Cap cap = Cap.of(container);
        return new ArgbCanvas(cap.x(), cap.y(), CAP_SIZE, CAP_SIZE);
    }

    /**
     * Cuts a flattened raster into tiles (gui.md §11.3 rules 2 and 3): the grid is anchored at the
     * painted bounds' top-left, a tile with no pixel is not emitted, and each remaining tile is cropped to its
     * ink box and padded with the fewest transparent rows that satisfy {@code ascent <= height}. Tiles are
     * in row-major order. A canvas with no painted pixel yields no tile. Ascents are those of {@code container}'s
     * title row.
     *
     * @throws CompileRejectException ({@code MTR-0091}) when a tile breaks an emit-time invariant
     */
    static List<Tile> cut(ArgbCanvas canvas, GuiContainer container) {
        ArgbCanvas.Bounds bounds = canvas.paintedBounds();
        var tiles = new ArrayList<Tile>();
        if (bounds == null) {
            return tiles;
        }
        int rows = (bounds.height() + TILE - 1) / TILE;
        int columns = (bounds.width() + TILE - 1) / TILE;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int cellX = bounds.x() + col * TILE;
                int cellY = bounds.y() + row * TILE;
                int cellWidth = Math.min(TILE, bounds.x() + bounds.width() - cellX);
                int cellHeight = Math.min(TILE, bounds.y() + bounds.height() - cellY);
                Tile tile = tile(canvas, container, row, col, cellX, cellY, cellWidth, cellHeight);
                if (tile != null) {
                    tiles.add(tile);
                }
            }
        }
        return tiles;
    }

    /** The tile inside one grid cell, cropped to its ink, or {@code null} when the cell is transparent. */
    private static Tile tile(ArgbCanvas canvas, GuiContainer container, int row, int col, int cellX, int cellY,
            int cellWidth, int cellHeight) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (int y = 0; y < cellHeight; y++) {
            for (int x = 0; x < cellWidth; x++) {
                if ((canvas.argb(cellX + x, cellY + y) >>> 24) != 0) {
                    left = Math.min(left, x);
                    right = Math.max(right, x + 1);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y + 1);
                }
            }
        }
        if (left == Integer.MAX_VALUE) {
            return null;
        }
        int tx = cellX + left;
        int ty = cellY + top;
        int tw = right - left;
        int th = bottom - top;
        int ascent = GuiTitle.ascentFor(container, ty);
        int cell = Math.max(th, ascent);
        check(row, col, tx, ty, tw, cell, ascent, Cap.of(container).y());
        int[] pixels = new int[tw * cell];
        System.arraycopy(canvas.crop(tx, ty, tw, th), 0, pixels, 0, tw * th);
        return new Tile(row, col, tx, ty, tw, th, ascent, cell, pixels);
    }

    /**
     * The emit-time invariants of a font provider (gui.md §11.3 rule 4): {@code a <= H},
     * {@code 1 <= tw <= 256}, {@code 1 <= H <= 256} and {@code ty >= capTop} (−243 for the profiles' title row). One provider with
     * {@code a > H} makes the client drop the whole GUI font of the pack (§9.10), so a tile that breaks any of
     * them is never emitted.
     *
     * @throws CompileRejectException ({@code MTR-0091}) naming the tile and the invariant
     */
    static void check(int row, int col, int tx, int ty, int tw, int cell, int ascent, int capTop) {
        String broken = null;
        if (ascent > cell) {
            broken = "ascent " + ascent + " is higher than height " + cell;
        } else if (tw < 1 || tw > TILE) {
            broken = "width " + tw + " is outside 1.." + TILE;
        } else if (cell < 1 || cell > TILE) {
            broken = "height " + cell + " is outside 1.." + TILE;
        } else if (ty < capTop) {
            broken = "top row " + ty + " is above the highest row the client draws (" + capTop + ")";
        }
        if (broken != null) {
            throw new CompileRejectException(DiagnosticCodes.GUI_ART_GEOMETRY_INVALID,
                    "art tile " + row + "/" + col + " at (" + tx + ", " + ty + ") cannot become a font provider: " + broken,
                    "this is an internal limit the art cap normally prevents; report it with the GUI that caused it");
        }
    }
}
