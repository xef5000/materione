package dev.materione.content.gui;

/**
 * Where the compiler gets the codepoint of an art tile (gui.md §4 step 3, §11.3 rule 3). Asked once per
 * emitted tile and never for a tile that is not emitted, so a GUI that paints nothing allocates nothing.
 * The pack compile answers from the durable {@code gui-background} allocation; the runtime answers from the
 * same table without allocating; a test may answer with anything.
 */
@FunctionalInterface
public interface GuiCodepoints {

    /**
     * The codepoint of one tile.
     *
     * @param layer the id of the raster layer whose overlay glyph this is, or {@code null} for the GUI's
     *     static background
     * @param frame the layer's animation frame (0 for the background and for a layer without frames)
     * @param row the tile's grid row, counted from the painted bounds' top-left
     * @param col the tile's grid column
     * @return a BMP private-use codepoint, unique per (GUI, layer, frame, row, col)
     */
    int codepoint(String layer, int frame, int row, int col);
}
