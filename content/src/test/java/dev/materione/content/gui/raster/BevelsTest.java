package dev.materione.content.gui.raster;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The bevel classification of the shared README on a 6 × 6 button and a 4 × 4 inset. */
class BevelsTest {

    private static final int OUTLINE = 0xFF000001;
    private static final int HIGHLIGHT = 0xFF000002;
    private static final int SHADOW = 0xFF000003;
    private static final int FILL = 0xFF000004;

    @Test
    void raisedOneBevelHasTransparentCornersOutlineRingAndCornerRule() {
        var canvas = new ArgbCanvas(6, 6);
        Bevels.raised(canvas, 0, 0, 6, 6, 1, OUTLINE, HIGHLIGHT, SHADOW, FILL);
        // ring corners are not drawn
        assertThat(canvas.argb(0, 0)).isZero();
        assertThat(canvas.argb(5, 0)).isZero();
        assertThat(canvas.argb(0, 5)).isZero();
        assertThat(canvas.argb(5, 5)).isZero();
        // ring
        assertThat(canvas.argb(1, 0)).isEqualTo(OUTLINE);
        assertThat(canvas.argb(0, 3)).isEqualTo(OUTLINE);
        assertThat(canvas.argb(5, 3)).isEqualTo(OUTLINE);
        // inside: top-left highlight, bottom-right shadow, off-diagonal corner blocks fill
        assertThat(canvas.argb(1, 1)).isEqualTo(HIGHLIGHT);
        assertThat(canvas.argb(4, 4)).isEqualTo(SHADOW);
        assertThat(canvas.argb(4, 1)).isEqualTo(FILL);
        assertThat(canvas.argb(1, 4)).isEqualTo(FILL);
        assertThat(canvas.argb(2, 2)).isEqualTo(FILL);
    }

    @Test
    void insetWellHasShadowTopLeftHighlightBottomRightAndNoOutline() {
        var canvas = new ArgbCanvas(4, 4);
        Bevels.inset(canvas, 0, 0, 4, 4, HIGHLIGHT, SHADOW, FILL);
        assertThat(canvas.argb(0, 0)).isEqualTo(SHADOW);
        assertThat(canvas.argb(2, 0)).isEqualTo(SHADOW);
        assertThat(canvas.argb(0, 2)).isEqualTo(SHADOW);
        assertThat(canvas.argb(3, 3)).isEqualTo(HIGHLIGHT);
        assertThat(canvas.argb(3, 1)).isEqualTo(HIGHLIGHT);
        assertThat(canvas.argb(3, 0)).isEqualTo(FILL); // top-right: both flags, fill
        assertThat(canvas.argb(0, 3)).isEqualTo(FILL); // bottom-left: both flags, fill
        assertThat(canvas.argb(1, 1)).isEqualTo(FILL);
    }

    @Test
    void twoPixelPanelUsesTwoPixelEdgesAndSmallPanelsNeedNoSpecialCase() {
        var canvas = new ArgbCanvas(8, 8);
        Bevels.raised(canvas, 0, 0, 8, 8, 2, OUTLINE, HIGHLIGHT, SHADOW, FILL);
        assertThat(canvas.argb(1, 1)).isEqualTo(HIGHLIGHT);
        assertThat(canvas.argb(2, 2)).isEqualTo(HIGHLIGHT);
        assertThat(canvas.argb(3, 3)).isEqualTo(FILL);
        assertThat(canvas.argb(5, 5)).isEqualTo(SHADOW);
        assertThat(canvas.argb(6, 6)).isEqualTo(SHADOW);

        // a 2 × 2 rectangle is all ring and all corner: every pixel is a transparent corner
        var tiny = new ArgbCanvas(2, 2);
        Bevels.raised(tiny, 0, 0, 2, 2, 2, OUTLINE, HIGHLIGHT, SHADOW, FILL);
        assertThat(tiny.toArray()).containsOnly(0);
    }

    @Test
    void emptyRectanglesDrawNothing() {
        var canvas = new ArgbCanvas(4, 4);
        Bevels.raised(canvas, 0, 0, 0, 3, 1, OUTLINE, HIGHLIGHT, SHADOW, FILL);
        Bevels.inset(canvas, 0, 0, 3, 0, HIGHLIGHT, SHADOW, FILL);
        assertThat(canvas.toArray()).containsOnly(0);
    }
}
