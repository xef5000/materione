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
    }
}
