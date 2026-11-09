package dev.materione.content.gui;

import dev.materione.api.gui.GuiContainer;
import dev.materione.api.gui.TitleSink;
import java.util.List;

/**
 * Title assembly (gui.md §4, step 4): the MiniMessage title a compiled GUI sends as its
 * container title. Positions come from shift tags, which move the pen by a fixed advance,
 * so the assembler tracks the pen's position exactly and emits only the shifts it needs.
 *
 * <p><strong>Title draw origin.</strong> Vanilla draws a container's title at
 * {@code (titleLabelX, titleLabelY)} relative to the screen's top-left ({@code AbstractContainerScreen}, 26.1.2),
 * and the container profile says where (gui.md §12.5.3): {@code (8, 6)} for the chests and the hopper. The pen
 * starts there ({@link #originX}), and every piece of the title (art, text) begins and ends at it, so pieces
 * assemble in any order.
 *
 * <p><strong>Centred titles.</strong> A screen that centres its title (the dispenser's) sets {@code titleLabelX} to
 * {@code (imageWidth − width) / 2}, with {@code width} the sum of the title's advances, shift characters included
 * and negative ones unclamped (verified in game, gui.md §12.2.6). The compiler knows every advance, so it makes the
 * width a constant: after the last piece the pen is shifted to {@code origin + imageWidth − 16}, the title is
 * {@code imageWidth − 16} wide and the client's origin is {@link #CENTRED_ORIGIN_X}, the same pen start as everywhere
 * else. A profile whose title is not centred needs no such shift and gets none, so its strings are unchanged.
 *
 * <p><strong>Vertical rule.</strong> The client places a bitmap glyph's top at
 * {@code drawY + 7 − ascent} ({@code GlyphBitmap.getTop}, 26.1.2), where {@code drawY} is
 * the profile's title row. With {@code drawY = 6}, a glyph whose top must land on screen row
 * {@code t} has {@code ascent = 13 − t}. An art tile whose top is row {@code ty} has ascent
 * {@code 13 − ty}; a text run on row {@code y} uses an offset font with ascent {@code 13 − y}
 * ({@link #ascentFor}: {@code titleY + 7 − t} in general).
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

    /**
     * The pen's starting column on a screen that centres its title: with the trailing shift the title is
     * {@code imageWidth − 2·CENTRED_ORIGIN_X} wide, so {@code (imageWidth − width) / 2} is this, whatever the width.
     */
    static final int CENTRED_ORIGIN_X = 8;

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
    private final GuiContainer container;
    private final StringBuilder out = new StringBuilder();
    /** The pen's absolute GUI column; starts at the container's title origin. */
    private int pen;

    /** Starts an empty title with the pen at {@code container}'s title origin. */
    public GuiTitle(String namespace, GuiContainer container) {
        this.namespace = namespace;
        this.container = container;
        this.pen = originX(container);
    }

    /** The pen's starting column for {@code container}: its {@code titleLabelX}, or the centred origin. */
    static int originX(GuiContainer container) {
        return container.titleCentred() ? CENTRED_ORIGIN_X : container.titleX();
    }

    /** Where a centred title's pen is shifted to at the end: {@code origin + imageWidth − 16} (168 on a 176-px screen). */
    static int trailingX(GuiContainer container) {
        return originX(container) + container.width() - 2 * CENTRED_ORIGIN_X;
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
        moveTo(originX(container));
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

    /**
     * The assembled title. On a profile with a centred title, a title that has any piece ends with the trailing
     * shift (gui.md §12.5.3), nothing when the pen is already there; a title with no piece is empty, and the
     * client centres the plain title as vanilla does.
     */
    public String build() {
        if (!container.titleCentred() || out.isEmpty()) {
            return out.toString();
        }
        int offset = trailingX(container) - pen;
        return offset == 0 ? out.toString() : out + "<shift:" + offset + ">";
    }

    /** The ascent that places a glyph's top on screen row {@code screenTop} in {@code container}'s title. */
    static int ascentFor(GuiContainer container, int screenTop) {
        return container.titleY() + BASELINE - screenTop;
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
