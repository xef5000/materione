package dev.materione.content.gui.raster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The pixel rules of gui.md §4 on hand-computed values (the vectors cover the rest). */
class ArgbCanvasTest {

    @Test
    void transparentSourceWritesNothingAndOpaqueSourceReplaces() {
        assertThat(ArgbCanvas.over(0x00FF0000, 0xFF123456)).isEqualTo(0xFF123456);
        assertThat(ArgbCanvas.over(0xFFFF0000, 0xFF123456)).isEqualTo(0xFFFF0000);
        assertThat(ArgbCanvas.over(0x80FF0000, 0x00000000)).isEqualTo(0x80FF0000);
    }

    @Test
    void halfRedOverBlackRoundsHalfUpPerChannel() {
        // sa = 128, da = 255: den = 255², outA = 255, red = floor((2·128·255·255 + den) / 2·den) = 128
        assertThat(ArgbCanvas.over(0x80FF0000, 0xFF000000)).isEqualTo(0xFF800000);
    }

    @Test
    void rdivRoundsHalfUp() {
        assertThat(ArgbCanvas.rdiv(49, 100)).isZero();
        assertThat(ArgbCanvas.rdiv(50, 100)).isEqualTo(1);
        assertThat(ArgbCanvas.rdiv(255 * 50, 100)).isEqualTo(128);
    }

    @Test
    void layerOpacityScalesSourceAlphaWithRoundHalfUp() {
        var layer = new ArgbCanvas(1, 1);
        layer.fill(0, 0, 1, 1, 0xFFFFFFFF);
        var output = new ArgbCanvas(1, 1);
        output.composite(layer, 50);
        assertThat(output.argb(0, 0)).isEqualTo(0x80FFFFFF);

        var faint = new ArgbCanvas(1, 1);
        faint.composite(layer, 1); // sa = floor((255 + 50) / 100) = 3
        assertThat(faint.argb(0, 0)).isEqualTo(0x03FFFFFF);
    }

    @Test
    void writesOutsideTheCanvasAreClipped() {
        var canvas = new ArgbCanvas(3, 2);
        canvas.blend(-1, 0, 0xFFFFFFFF);
        canvas.blend(3, 1, 0xFFFFFFFF);
        canvas.fill(2, 1, 5, 5, 0xFF00FF00);
        assertThat(canvas.argb(2, 1)).isEqualTo(0xFF00FF00);
        assertThat(canvas.argb(0, 0)).isZero();
        assertThat(canvas.toArray()).hasSize(6);
    }

    @Test
    void inkWidthIsTheRightmostOpaqueColumnPlusOne() {
        var canvas = new ArgbCanvas(8, 4);
        assertThat(canvas.inkWidth()).isZero();
        canvas.blend(5, 2, 0xFF000000);
        assertThat(canvas.inkWidth()).isEqualTo(6);
    }

    @Test
    void compositeRequiresEqualSizes() {
        assertThatThrownBy(() -> new ArgbCanvas(2, 2).composite(new ArgbCanvas(3, 2), 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArgbCanvas(2, 2).composite(new ArgbCanvas(1, 0, 2, 2), 100))
                .as("the same size at another origin is another rectangle")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- the open canvas (gui.md §11): an origin, painted bounds, clipping memory ---------------------------

    @Test
    void anOriginMakesEveryCoordinateAGuiPixel() {
        var canvas = new ArgbCanvas(-10, -5, 20, 10);
        canvas.blend(-10, -5, 0xFF111111);
        canvas.blend(9, 4, 0xFF222222);
        canvas.fill(-2, 0, 3, 1, 0xFF333333);
        assertThat(canvas.argb(-10, -5)).isEqualTo(0xFF111111);
        assertThat(canvas.argb(9, 4)).isEqualTo(0xFF222222);
        assertThat(canvas.argb(-2, 0)).isEqualTo(0xFF333333);
        assertThat(canvas.argb(0, 0)).isEqualTo(0xFF333333);
        assertThat(canvas.argb(1, 0)).isZero();
        assertThat(canvas.argb(-11, -5)).as("outside is 0").isZero();
        assertThat(canvas.originX()).isEqualTo(-10);
        assertThat(canvas.originY()).isEqualTo(-5);
    }

    @Test
    void paintedBoundsAreTheBoxOfPixelsWithAlphaAboveZero() {
        var canvas = new ArgbCanvas(-10, -5, 20, 10);
        assertThat(canvas.paintedBounds()).isNull();
        canvas.blend(0, 0, 0x00FFFFFF); // a transparent source paints nothing
        assertThat(canvas.paintedBounds()).isNull();
        canvas.blend(-3, -2, 0x01000000);
        canvas.blend(4, 3, 0xFF000000);
        assertThat(canvas.paintedBounds()).isEqualTo(new ArgbCanvas.Bounds(-3, -2, 8, 6));
        // a fill that erases leaves no ink, so the bounds shrink to what is still painted
        canvas.fill(4, 3, 1, 1, 0);
        assertThat(canvas.paintedBounds()).isEqualTo(new ArgbCanvas.Bounds(-3, -2, 1, 1));
    }

    @Test
    void cropCopiesABlockOfGuiPixelsAndPadsOutsideWithZero() {
        var canvas = new ArgbCanvas(-2, -2, 6, 6);
        canvas.fill(-1, -1, 2, 2, 0xFFABCDEF);
        assertThat(canvas.crop(-2, -2, 3, 3)).containsExactly(
                0, 0, 0,
                0, 0xFFABCDEF, 0xFFABCDEF,
                0, 0xFFABCDEF, 0xFFABCDEF);
        assertThat(canvas.crop(3, 3, 2, 2)).as("past the edge").containsOnly(0);
    }

    @Test
    void clippedInkRemembersAVisibleWriteThatFellOutsideAndClearForgetsIt() {
        var canvas = new ArgbCanvas(0, 0, 4, 4);
        canvas.blend(9, 9, 0x00FFFFFF);
        canvas.fill(7, 7, 2, 2, 0x00000000);
        assertThat(canvas.clippedInk()).as("transparent writes are not ink").isFalse();
        canvas.fill(2, 2, 5, 1, 0xFF00FF00); // half inside, half outside
        assertThat(canvas.clippedInk()).isTrue();
        assertThat(canvas.argb(3, 2)).isEqualTo(0xFF00FF00);
        canvas.clear();
        assertThat(canvas.clippedInk()).isFalse();
        assertThat(canvas.paintedBounds()).isNull();
        assertThat(canvas.argb(3, 2)).as("clear really clears the written rectangle").isZero();
        canvas.blend(-1, 0, 0xFF000000);
        assertThat(canvas.clippedInk()).isTrue();
    }

    @Test
    void compositeOnlyVisitsWhatTheLayerWroteAndMatchesAFullComposite() {
        var layer = new ArgbCanvas(-4, -4, 8, 8);
        layer.fill(-2, -1, 3, 2, 0xFF336699);
        layer.blend(2, 2, 0x80FF0000);
        var base = new ArgbCanvas(-4, -4, 8, 8);
        base.fill(-4, -4, 8, 8, 0xFFFFFFFF);
        base.composite(layer, 60);
        assertThat(base.argb(-2, -1)).isEqualTo(ArgbCanvas.over(0x99336699, 0xFFFFFFFF));
        assertThat(base.argb(2, 2)).isEqualTo(ArgbCanvas.over(
                (ArgbCanvas.rdiv(0x80 * 60, 100) << 24) | 0xFF0000, 0xFFFFFFFF));
        assertThat(base.argb(-4, -4)).as("untouched").isEqualTo(0xFFFFFFFF);
    }
}
