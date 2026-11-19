package dev.materione.core.text;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeGlyphs;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Frame-cycling math and the registry's animation index (runtime-systems §6, M6, #9). */
class GlyphFramesTest {

    @Test
    void framesPlayInOrderForFrameTicksEachAndWrap() {
        // 3 frames, 4 ticks each: 0-3 -> 0, 4-7 -> 1, 8-11 -> 2, 12 wraps to 0
        assertThat(GlyphFrames.frameIndex(0, 3, 4)).isZero();
        assertThat(GlyphFrames.frameIndex(3, 3, 4)).isZero();
        assertThat(GlyphFrames.frameIndex(4, 3, 4)).isEqualTo(1);
        assertThat(GlyphFrames.frameIndex(8, 3, 4)).isEqualTo(2);
        assertThat(GlyphFrames.frameIndex(11, 3, 4)).isEqualTo(2);
        assertThat(GlyphFrames.frameIndex(12, 3, 4)).isZero();
    }

    @Test
    void aStillGlyphAndANegativeClockAlwaysShowFrameOne() {
        assertThat(GlyphFrames.frameIndex(999, 1, 4)).isZero();
        assertThat(GlyphFrames.frameIndex(999, 0, 4)).isZero();
        assertThat(GlyphFrames.frameIndex(-50, 3, 4)).isZero();
    }

    @Test
    void anOutOfRangeSpeedIsClampedNotRejected() {
        assertThat(GlyphFrames.clampFrameTicks(0)).isEqualTo(GlyphFrames.MIN_FRAME_TICKS);
        assertThat(GlyphFrames.clampFrameTicks(-9)).isEqualTo(GlyphFrames.MIN_FRAME_TICKS);
        assertThat(GlyphFrames.clampFrameTicks(1_000_000)).isEqualTo(GlyphFrames.MAX_FRAME_TICKS);
        assertThat(GlyphFrames.frameIndex(5, 2, 0)).isEqualTo(1); // 0 reads as 1 tick per frame
    }

    @Test
    void registryIndexesAnimationsByFirstFrameAndSignsFrameChanges() {
        var spark = entry("sample:spark", new int[] {42000, 42001, 42002}, 4);
        var still = entry("sample:sheet", new int[] {42010, 42011}, 0); // a sheet: two codepoints, no cycle
        var alias = entry("sample:alias", new int[0], 0);
        var entries = new LinkedHashMap<NamespacedId, RuntimeGlyphs.Entry>();
        for (var e : List.of(spark, still, alias)) {
            entries.put(e.id(), e);
        }
        var glyphs = new RuntimeGlyphs(entries);

        assertThat(glyphs.hasAnimations()).isTrue();
        assertThat(glyphs.animationStartingAt(42000)).contains(spark);
        assertThat(glyphs.animationStartingAt(42001)).isEmpty(); // a later frame is a pin, not the animation
        assertThat(glyphs.animationStartingAt(42010)).isEmpty(); // a sheet never cycles
        assertThat(spark.frameAt(0)).isEqualTo(42000);
        assertThat(spark.frameAt(4)).isEqualTo(42001);
        assertThat(spark.frameAt(8)).isEqualTo(42002);
        // the signature moves exactly when a frame does
        assertThat(glyphs.frameSignature(0)).isEqualTo(glyphs.frameSignature(3));
        assertThat(glyphs.frameSignature(0)).isNotEqualTo(glyphs.frameSignature(4));
        assertThat(glyphs.frameSignature(0)).isEqualTo(glyphs.frameSignature(12));
        assertThat(RuntimeGlyphs.empty().hasAnimations()).isFalse();
    }

    private static RuntimeGlyphs.Entry entry(String id, int[] codepoints, int frameTicks) {
        return new RuntimeGlyphs.Entry(NamespacedId.parse(id), null, codepoints, List.of(), null, false, false,
                null, null, null, frameTicks);
    }
}
