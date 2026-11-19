package dev.materione.plugin.gui.render;

/**
 * The frame an appearance's animated glyphs show (runtime-systems §6, M6, #9): the window's clock the frames are
 * selected from, and the signature of the frames that clock selects. Two phases are equal when they select the
 * same frames, so {@link RenderDiff} rewrites a slot only when an animation moved, not on every refresh tick.
 *
 * @param ticks the window's clock, applied when the stack is built
 * @param signature {@link dev.materione.core.runtime.RuntimeGlyphs#frameSignature} at {@code ticks}
 */
public record GlyphPhase(long ticks, int signature) {

    @Override
    public boolean equals(Object other) {
        return other instanceof GlyphPhase phase && phase.signature == signature;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(signature);
    }
}
