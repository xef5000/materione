package dev.materione.content.gui;

import dev.materione.api.gui.TitleSink;
import java.util.List;

/**
 * Title assembly (gui.md §4, step 4): the MiniMessage title a compiled GUI sends as its
 * container title. Positions come from shift tags, which move the pen by a fixed advance,
 * so the assembler tracks the pen's position exactly and emits only the shifts it needs.
 *
 * <p><strong>Title draw origin.</strong> Vanilla draws a container's title at
 * {@code (titleLabelX, titleLabelY) = (8, 6)} relative to the screen's top-left
 * ({@code AbstractContainerScreen}, 26.1.2). The pen starts there, and every piece of the title
 * (art, text) begins and ends at {@link #ORIGIN_X}, so pieces assemble in any order.
 *
 * <p><strong>Vertical rule.</strong> The client places a bitmap glyph's top at
 * {@code drawY + 7 − ascent} ({@code GlyphBitmap.getTop}, 26.1.2), where {@code drawY} is
 * the text origin's row. With {@code drawY = 6}, a glyph whose top must land on screen row
 * {@code t} has {@code ascent = 13 − t}. An art tile whose top is row {@code ty} has ascent
 * {@code 13 − ty}; a text run on row {@code y} uses an offset font with ascent {@code 13 − y}.
 *
 * <p><strong>Horizontal rule.</strong> A bitmap glyph is drawn at the pen and advances by its ink
 * width plus one ({@code actualWidth + 1}). To draw a tile whose left edge is GUI column {@code x}
 * the pen is shifted by {@code x − pen} first, and after it the pen is at {@code x + inkWidth + 1}
 * (gui.md §11.3 rule 5). A tile at column 0 is drawn from pen position −8, so the first shift of a
 * screen-wide background is −8, as before the open canvas.
 *
 * <p>Text advances are known for both fonts: {@code smallcaps} from its own pixels
 * ({@link SmallcapsFont}), {@code default} from the vanilla ASCII table ({@link VanillaAscii}, gui.md
 * section 9.4). Every run therefore moves the pen by a known amount, and any run may follow any other.
 */
public final class GuiTitle {

    /** Vanilla {@code titleLabelX}: the pen's starting column. */
    static final int ORIGIN_X = 8;

    /** Vanilla {@code titleLabelY}: the text origin's row. */
    static final int ORIGIN_Y = 6;

    /** {@code GlyphBitmap.getTop()} is {@code 7 − ascent} above the draw row. */
    static final int BASELINE = 7;

    /**
     * One art glyph as the title needs it.
     *
     * @param codepoint the tile's codepoint in the GUI font
     * @param x the GUI column of the tile's left edge (its first inked column)
     * @param inkWidth the tile's ink width, so its advance is {@code inkWidth + 1}
     */
    public record ArtGlyph(int codepoint, int x, int inkWidth) {}

    private final String namespace;
    private final StringBuilder out = new StringBuilder();
    /** The pen's absolute GUI column; starts at {@link #ORIGIN_X}. */
    private int pen = ORIGIN_X;

    /** Starts an empty title with the pen at the vanilla title origin. */
    public GuiTitle(String namespace) {
        this.namespace = namespace;
    }

    /**
     * Appends one art piece: its glyphs in the order given (row-major), each shifted to its own column,
     * then a shift back to the origin. Art goes first in a title, so that text draws over it. An empty
     * list appends nothing.
     */
    public void art(List<ArtGlyph> glyphs) {
        for (ArtGlyph glyph : glyphs) {
            moveTo(glyph.x());
            out.append("<font:").append(GuiFonts.background(namespace)).append("><#FFFFFF>")
                    .appendCodePoint(glyph.codepoint()).append("</font>");
            pen = glyph.x() + glyph.inkWidth() + 1;
        }
        moveTo(ORIGIN_X);
    }

    /**
     * Appends one text run whose top-left is {@code (x, y)} in GUI pixels. The caller has
     * checked the run's characters and row.
     */
    public void place(int x, int y, String text, TitleSink.Font font, int argb) {
        moveTo(x);
        out.append("<font:").append(GuiFonts.text(namespace, font, y)).append("><#")
                .append(hex(argb)).append('>').append(text).append("</font>");
        pen += font == TitleSink.Font.SMALLCAPS ? SmallcapsFont.measure(text) : VanillaAscii.measure(text);
    }

    public String build() {
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
