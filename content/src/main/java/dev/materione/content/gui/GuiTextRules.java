package dev.materione.content.gui;

import dev.materione.api.gui.TitleSink;

/** Shared title text validation, including values produced at render time. */
public final class GuiTextRules {
    private GuiTextRules() {}

    public static String problem(String text, TitleSink.Font font, int y, boolean template) {
        if (font == TitleSink.Font.DEFAULT && GuiTitle.ascentFor(y) > GuiFonts.VANILLA_ASCII_HEIGHT) {
            return "default-font text must start on row 5 or lower";
        }
        if (template) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if ("<\\&§".indexOf(character) >= 0) {
                return "text may not contain <, \\, & or §";
            }
            if (!(font == TitleSink.Font.SMALLCAPS
                    ? SmallcapsFont.supports(character) : VanillaAscii.supports(character))) {
                return "the font draws printable ASCII only";
            }
        }
        return null;
    }

    public static java.util.Set<Integer> slots(int x, int y, String text, TitleSink.Font font, int rows) {
        int width = font == TitleSink.Font.SMALLCAPS ? SmallcapsFont.measure(text) : VanillaAscii.measure(text);
        int height = font == TitleSink.Font.SMALLCAPS ? SmallcapsFont.GLYPH_ROWS : GuiFonts.VANILLA_ASCII_HEIGHT;
        return java.util.Set.copyOf(dev.materione.content.gui.logic.ClickMapCompiler.coveredSlots(
                new dev.materione.api.gui.ClickArea.Rect(x, y, width, height), rows));
    }
}
