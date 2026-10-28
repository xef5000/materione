package dev.materione.content.gui;

/**
 * The background glyph codepoints (gui.md §4, step 3). Each GUI owns one durable
 * allocation of kind {@link #ALLOCATION_KIND}; its value {@code n} maps to the codepoint
 * {@code ORIGIN + n - 1}. Glyph codepoints occupy {@code U+A410..U+E100} and the shift font
 * {@code U+E101..U+E11A}; this block starts above both, so the three spaces never collide.
 * It is BMP-only, as glyph codepoints are, because text components address codepoints as
 * UTF-16 units.
 */
public final class GuiBackgrounds {

    /** The allocation kind backing a GUI's background codepoint (durable, never reused). */
    public static final String ALLOCATION_KIND = "gui-background";

    /** Codepoint of allocation number 1: U+E200. */
    public static final int ORIGIN = 0xE200;

    /** The last codepoint of the block: U+F8FF, the end of the BMP private-use area. */
    public static final int LAST = 0xF8FF;

    private GuiBackgrounds() {}

    /** Whether allocation number {@code n} (1-based) still lies inside the block. */
    public static boolean fits(int n) {
        return n >= 1 && ORIGIN + n - 1 <= LAST;
    }

    /**
     * The background codepoint of allocation number {@code n} (1-based).
     *
     * @throws IllegalArgumentException when {@code n} is outside the block
     */
    public static int codepoint(int n) {
        if (!fits(n)) {
            throw new IllegalArgumentException("GUI background allocation " + n + " is outside U+E200..U+F8FF");
        }
        return ORIGIN + n - 1;
    }
}
