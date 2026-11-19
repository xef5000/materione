package dev.materione.core.text;

/**
 * Frame-cycling math for animated glyphs (runtime-systems §6, M6, #9): which frame an animation shows at a given
 * clock tick. Pure and deterministic, so every surface (a GUI window's clock, a HUD element's per-player clock)
 * and every test agrees on one answer. The clock is the surface's own tick counter; a glyph's frames play in
 * order, each for {@code frameTicks} ticks, and wrap.
 */
public final class GlyphFrames {

    /** Ticks each frame shows when the glyph does not say (5 frames per second). */
    public static final int DEFAULT_FRAME_TICKS = 4;
    /** Fastest cycle: a new frame every tick. */
    public static final int MIN_FRAME_TICKS = 1;
    /** Slowest cycle: a frame a minute. */
    public static final int MAX_FRAME_TICKS = 1200;

    private GlyphFrames() {}

    /** An authored {@code frame_ticks} brought into range; fail-soft, never an error. */
    public static int clampFrameTicks(int authored) {
        return Math.max(MIN_FRAME_TICKS, Math.min(MAX_FRAME_TICKS, authored));
    }

    /**
     * The 0-based frame an animation of {@code frameCount} frames shows at {@code tick}.
     *
     * @param tick the surface's clock; a negative clock reads as tick 0
     * @param frameCount frames in the glyph; fewer than 2 means nothing to cycle (always frame 0)
     * @param frameTicks ticks per frame, clamped into range
     */
    public static int frameIndex(long tick, int frameCount, int frameTicks) {
        if (frameCount < 2) {
            return 0;
        }
        long step = Math.max(0, tick) / clampFrameTicks(frameTicks);
        return (int) (step % frameCount);
    }
}
