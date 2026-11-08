package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Collision-free allocation identities and asset paths for GUI art tiles (gui.md §11.3 rule 3).
 *
 * <p>The first tile of a GUI's background (grid row 0, column 0) keeps the identity and the texture path a
 * GUI had before the open canvas: the GUI's own id, and {@code font/materione/gui/<id>}. So an existing GUI
 * keeps its codepoint and its PNG's path when its art is one tile. Every other tile appends
 * {@code tile/<row>/<col>} to the identity. An overlay glyph (a raster layer's frame) works the same way
 * with the layer and frame before the tile.
 */
public final class GuiOverlayKeys {
    private GuiOverlayKeys() {}

    /** The allocation identity of a background tile: the GUI itself for tile 0/0, else a tile key under it. */
    public static NamespacedId background(NamespacedId gui, int row, int col) {
        if (row == 0 && col == 0) {
            return gui;
        }
        return NamespacedId.of("minecraft", "materione_gui_tile/" + encode(gui.namespace()) + "/"
                + encode(gui.value()) + "/tile/" + row + "/" + col);
    }

    /** The texture of a background tile, relative to {@code assets/<ns>/textures/}, without the extension. */
    public static String backgroundTexture(NamespacedId gui, int row, int col) {
        if (row == 0 && col == 0) {
            return "font/materione/gui/" + gui.value();
        }
        return "font/materione/gui_tile/" + encode(gui.value()) + "/" + row + "_" + col;
    }

    public static NamespacedId allocation(NamespacedId gui, String layer, int frame) {
        return NamespacedId.of("minecraft", "materione_gui_overlay/" + encode(gui.namespace()) + "/"
                + encode(gui.value()) + "/" + encode(layer) + "/" + frame);
    }

    /** The allocation identity of one tile of an overlay frame; tile 0/0 is {@link #allocation(NamespacedId, String, int)}. */
    public static NamespacedId allocation(NamespacedId gui, String layer, int frame, int row, int col) {
        if (row == 0 && col == 0) {
            return allocation(gui, layer, frame);
        }
        return NamespacedId.of("minecraft", "materione_gui_overlay/" + encode(gui.namespace()) + "/"
                + encode(gui.value()) + "/" + encode(layer) + "/" + frame + "/tile/" + row + "/" + col);
    }

    public static String texture(NamespacedId gui, String layer, int frame) {
        return "font/materione/gui_overlay/" + encode(gui.value()) + "/" + encode(layer) + "/" + frame;
    }

    /** The texture of one tile of an overlay frame; tile 0/0 is {@link #texture(NamespacedId, String, int)}. */
    public static String texture(NamespacedId gui, String layer, int frame, int row, int col) {
        if (row == 0 && col == 0) {
            return texture(gui, layer, frame);
        }
        return texture(gui, layer, frame) + "/" + row + "_" + col;
    }

    private static String encode(String text) {
        return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
    }
}
