package dev.materione.core.text;

/**
 * The glyph codepoint scheme: the allocation table hands out per-kind
 * sequential integers (1, 2, 3…) for kind {@code glyph-codepoint}; this maps
 * them into the private-use block the incumbents standardize on. The shift
 * font's block (U+E101+, see {@link ShiftMath}) must stay clear — the
 * compiler rejects a glyph whose run reaches it.
 */
public final class GlyphCodepoints {

    /** Codepoint of allocation number 1. */
    public static final int ORIGIN = 42_000;

    /** The first codepoint the shift font claims — allocations must stop below it. */
    public static final int SHIFT_BLOCK_START = 0xE101;

    private GlyphCodepoints() {}

    /** The codepoint for allocation number {@code n} (1-based). */
    public static int of(int n) {
        return ORIGIN - 1 + n;
    }
}
