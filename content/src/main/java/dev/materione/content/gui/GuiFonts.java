package dev.materione.content.gui;

import dev.materione.api.gui.TitleSink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The font keys and bitmap providers a compiled GUI emits (gui.md §4, steps 3–4).
 *
 * <p>Keys are namespace-qualified and merged per pack like glyph fonts: the GUI's
 * background glyph lives in {@code <ns>:gui}; a text run lives in an offset font
 * {@code <ns>:gui/<font>_y<y>} generated once per (font, y) actually used. Every
 * provider is a bitmap whose {@code ascent} places its top at the screen row the key
 * names (see {@link GuiTitle#ascentFor}).
 */
final class GuiFonts {

    /** The vanilla ASCII bitmap, referenced by resource location and never redistributed. */
    static final String VANILLA_ASCII = "minecraft:font/ascii.png";

    /** Pack-relative texture path (under {@code assets/<ns>/textures/}) of the small-caps sheet. */
    static final String SMALLCAPS_TEXTURE = "font/materione/smallcaps";

    /** Pack-relative texture folder of GUI background glyphs. */
    static final String BACKGROUND_TEXTURE_DIRECTORY = "font/materione/gui/";

    /** The vanilla ASCII cell is 8 px high; a larger ascent would scale it. */
    static final int VANILLA_ASCII_HEIGHT = 8;

    /** The advance of a space in the default font: the client's own space width. */
    static final int DEFAULT_SPACE_ADVANCE = 4;

    private GuiFonts() {}

    /** The GUI font that carries the background glyph. */
    static String background(String namespace) {
        return namespace + ":gui";
    }

    /** The offset font a text run at screen row {@code y} uses. */
    static String text(String namespace, TitleSink.Font font, int y) {
        String family = font == TitleSink.Font.SMALLCAPS ? "smallcaps" : "default";
        return namespace + ":gui/" + family + "_y" + y;
    }

    /** The asset path of a GUI's background texture, relative to the pack's {@code assets/}. */
    static String backgroundAsset(String namespace, String value) {
        return "assets/" + namespace + "/textures/" + BACKGROUND_TEXTURE_DIRECTORY + value + ".png";
    }

    /** The asset path of the small-caps sheet, relative to the pack's {@code assets/}. */
    static String smallcapsAsset(String namespace) {
        return "assets/" + namespace + "/textures/" + SMALLCAPS_TEXTURE + ".png";
    }

    /** The bitmap provider of a background glyph: one cell, the canvas's size, ascent at the top. */
    static Map<String, Object> backgroundProvider(String namespace, String value, int codepoint, int height) {
        return bitmap(namespace + ":" + BACKGROUND_TEXTURE_DIRECTORY + value + ".png",
                GuiTitle.ascentFor(0), height, List.of(String.valueOf((char) codepoint)));
    }

    /** The providers of one small-caps offset font: the space advance, then the sheet for row {@code y}. */
    static List<Map<String, Object>> smallcaps(String namespace, int y) {
        var providers = new ArrayList<Map<String, Object>>();
        providers.add(space(SmallcapsFont.SPACE_ADVANCE));
        providers.add(bitmap(namespace + ":" + SMALLCAPS_TEXTURE + ".png", GuiTitle.ascentFor(y),
                SmallcapsFont.CELL_HEIGHT, SmallcapsFont.grid()));
        return providers;
    }

    /** The providers of one default offset font: the space advance, then the vanilla ASCII grid at row {@code y}. */
    static List<Map<String, Object>> defaults(int y) {
        var providers = new ArrayList<Map<String, Object>>();
        providers.add(space(DEFAULT_SPACE_ADVANCE));
        providers.add(bitmap(VANILLA_ASCII, GuiTitle.ascentFor(y), VANILLA_ASCII_HEIGHT, asciiGrid()));
        return providers;
    }

    /**
     * The vanilla {@code ascii.png} grid, 16 rows of 16 cells in codepoint order. Only
     * printable ASCII is mapped; every other cell is {@code U+0000}, which the loader skips.
     */
    static List<String> asciiGrid() {
        var rows = new ArrayList<String>(16);
        for (int row = 0; row < 16; row++) {
            var line = new StringBuilder(16);
            for (int column = 0; column < 16; column++) {
                int codepoint = row * 16 + column;
                boolean printable = codepoint >= 0x20 && codepoint <= 0x7E && codepoint != ' ';
                line.append(printable ? (char) codepoint : '\u0000');
            }
            rows.add(line.toString());
        }
        return List.copyOf(rows);
    }

    private static Map<String, Object> bitmap(String file, int ascent, int height, List<String> chars) {
        var provider = new LinkedHashMap<String, Object>();
        provider.put("type", "bitmap");
        provider.put("file", file);
        provider.put("ascent", ascent);
        provider.put("height", height);
        provider.put("chars", List.copyOf(chars));
        return provider;
    }

    private static Map<String, Object> space(int advance) {
        var advances = new LinkedHashMap<String, Object>();
        advances.put(" ", advance);
        var provider = new LinkedHashMap<String, Object>();
        provider.put("type", "space");
        provider.put("advances", advances);
        return provider;
    }
}
