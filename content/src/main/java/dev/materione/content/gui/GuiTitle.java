package dev.materione.content.gui;

import dev.materione.api.gui.TitleSink;

/**
 * Title assembly (gui.md §4, step 4): the MiniMessage title a compiled GUI sends as its
 * container title. Positions come from shift tags, which move the pen by a fixed advance,
 * so the assembler tracks the pen's position exactly and emits only the shifts it needs.
 *
 * <p><strong>Title draw origin.</strong> Vanilla draws a container's title at
 * {@code (titleLabelX, titleLabelY) = (8, 6)} relative to the screen's top-left
 * ({@code AbstractContainerScreen}, 26.1.2). The pen starts there.
 *
 * <p><strong>Vertical rule.</strong> The client places a bitmap glyph's top at
 * {@code drawY + 7 − ascent} ({@code GlyphBitmap.getTop}, 26.1.2), where {@code drawY} is
 * the text origin's row. With {@code drawY = 6}, a glyph whose top must land on screen row
 * {@code t} has {@code ascent = 13 − t}. The background glyph sits on row 0, so its ascent is
 * 13; a text run on row {@code y} uses an offset font with ascent {@code 13 − y}.
 *
 * <p><strong>Horizontal rule.</strong> The pen starts at {@link #ORIGIN_X}. The background
 * is drawn from pen position {@code −8} (screen column 0). A bitmap glyph advances by its
 * ink width plus one ({@code actualWidth + 1}), so after it the pen is at
 * {@code −8 + advance}, and a shift of {@code 8 − advance} returns it to the origin.
 *
 * <p>Text advances are known only for {@code smallcaps} (its own widths). A {@code default}
 * run's advance would need the vanilla ASCII ink widths, which the compiler does not carry;
 * after such a run the pen is unknown, so {@link #canPlace()} is false for the next run.
 */
final class GuiTitle {

    /** Vanilla {@code titleLabelX}: the pen's starting column. */
    static final int ORIGIN_X = 8;

    /** Vanilla {@code titleLabelY}: the text origin's row. */
    static final int ORIGIN_Y = 6;

    /** {@code GlyphBitmap.getTop()} is {@code 7 − ascent} above the draw row. */
    static final int BASELINE = 7;

    private final String namespace;
    private final StringBuilder out = new StringBuilder();
    private int pen;
    private boolean penKnown = true;

    /**
     * Starts the title with the background glyph, which must be the first thing drawn so
     * that text draws over it.
     *
     * @param backgroundCodepoint the GUI's background codepoint
     * @param backgroundAdvance the glyph's advance, {@code actualWidth + 1}, from its pixels
     */
    GuiTitle(String namespace, int backgroundCodepoint, int backgroundAdvance) {
        this.namespace = namespace;
        moveTo(-ORIGIN_X);
        out.append("<font:").append(GuiFonts.background(namespace)).append("><#FFFFFF>")
                .appendCodePoint(backgroundCodepoint).append("</font>");
        pen = -ORIGIN_X + backgroundAdvance;
        moveTo(0);
    }

    /** Whether the pen position is known, so the next run can be placed. */
    boolean canPlace() {
        return penKnown;
    }

    /**
     * Appends one text run whose top-left is {@code (x, y)} in GUI pixels. The caller has
     * checked {@link #canPlace()} and the run's characters and row.
     */
    void place(int x, int y, String text, TitleSink.Font font, int argb) {
        moveTo(x - ORIGIN_X);
        out.append("<font:").append(GuiFonts.text(namespace, font, y)).append("><#")
                .append(hex(argb)).append('>').append(text).append("</font>");
        if (font == TitleSink.Font.SMALLCAPS) {
            pen += SmallcapsFont.measure(text);
        } else {
            penKnown = false;
        }
    }

    String build() {
        return out.toString();
    }

    /** The ascent that places a glyph's top on screen row {@code screenTop}. */
    static int ascentFor(int screenTop) {
        return ORIGIN_Y + BASELINE - screenTop;
    }

    private void moveTo(int target) {
        int offset = target - pen;
        if (offset != 0) {
            out.append("<shift:").append(offset).append('>');
        }
        pen = target;
    }

    /** {@code RRGGBB} of an ARGB colour; the alpha is not part of a title colour. */
    private static String hex(int argb) {
        String digits = "0123456789ABCDEF";
        var text = new StringBuilder(6);
        for (int shift = 20; shift >= 0; shift -= 4) {
            text.append(digits.charAt((argb >>> shift) & 0xF));
        }
        return text.toString();
    }
}
