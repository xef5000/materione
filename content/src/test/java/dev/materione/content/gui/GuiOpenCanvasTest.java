package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.CompileRejectException;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.gui.TextureSource;
import dev.materione.content.gui.raster.ArgbCanvas;
import dev.materione.core.texture.PngDecoder;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The open canvas compiler (gui.md §11.3): bounds, cap clipping, the 256-px tile grid, ascent and padding, the
 * pen arithmetic of the title, the emit-time invariants, an untextured GUI and the overlay path. Pure arithmetic
 * over panels (a panel of {@code w × h} has ink over its whole box), so no texture is needed.
 */
class GuiOpenCanvasTest {

    private static final NamespacedId BANNER = NamespacedId.of("shop", "banner");

    @TempDir
    Path temp;

    /** Codepoints handed out in the order the compiler asks, recording what it asked for. */
    private static final class Asked implements GuiCodepoints {
        final List<String> keys = new ArrayList<>();
        private int next = 0xE200;

        @Override
        public int codepoint(String layer, int frame, int row, int col) {
            keys.add((layer == null ? "bg" : layer + "#" + frame) + "/" + row + "/" + col);
            return next++;
        }
    }

    @SafeVarargs
    private static GuiCompilation compile(Asked asked, Map<String, Object>... layers) {
        var definition = new java.util.LinkedHashMap<String, Object>();
        definition.put("title", "Test");
        definition.put("rows", 1);
        var list = new ArrayList<Object>();
        for (Map<String, Object> layer : layers) {
            list.add(layer);
        }
        definition.put("layers", list);
        return GuiCompiler.compile(GuiTestSupport.parse(GuiTestSupport.type(), definition), TextureSource.NONE, asked);
    }

    private static Map<String, Object> panel(String id, int x, int y, int width, int height, Object... more) {
        var fields = new ArrayList<Object>(List.of("x", x, "y", y, "width", width, "height", height));
        fields.addAll(List.of(more));
        return GuiTestSupport.layer(id, "panel", fields.toArray());
    }

    private static GuiArt.Tile onlyTile(ArgbCanvas canvas) {
        List<GuiArt.Tile> tiles = GuiArt.cut(canvas);
        assertThat(tiles).hasSize(1);
        return tiles.get(0);
    }

    // --- paint layers own their position (#277) -------------------------------------------------------

    @Test
    void aPaintLayerDrawsAtItsOwnPositionAndAnEmptyOneNeedsNoTexture() {
        var definition = new java.util.LinkedHashMap<String, Object>();
        definition.put("title", "Test");
        definition.put("rows", 1);
        definition.put("layers", List.of(
                GuiTestSupport.layer("art", "paint", "image", "gui/art", "x", -30, "y", 5, "width", 2, "height", 3),
                GuiTestSupport.layer("blank", "paint", "image", "gui/never_saved", "x", 40, "y", 40,
                        "width", 0, "height", 0),
                GuiTestSupport.layer("legacy", "paint", "image", "gui/legacy")));
        int[] opaque = new int[6];
        java.util.Arrays.fill(opaque, 0xFF102030);
        TextureSource textures = texture -> switch (texture) {
            case "gui/art" -> java.util.Optional.of(new TextureSource.Pixels(2, 3, opaque));
            case "gui/legacy" -> java.util.Optional.of(new TextureSource.Pixels(1, 1, new int[] {0xFF405060}));
            default -> java.util.Optional.empty();
        };
        var skipped = new ArrayList<String>();
        ArgbCanvas canvas = GuiFlatten.flatten(GuiArt.CAP_X, GuiArt.CAP_Y, GuiArt.CAP_SIZE, GuiArt.CAP_SIZE,
                GuiTestSupport.parse(GuiTestSupport.type(), definition).layers(), textures,
                (layer, reason) -> skipped.add(layer.id() + ": " + reason), layer -> { });
        assertThat(skipped).isEmpty();
        assertThat(canvas.argb(-30, 5)).isEqualTo(0xFF102030);
        assertThat(canvas.argb(-29, 7)).isEqualTo(0xFF102030);
        assertThat(canvas.argb(0, 0)).isEqualTo(0xFF405060);
        assertThat(canvas.paintedBounds()).isEqualTo(new ArgbCanvas.Bounds(-30, 0, 31, 8));
    }

    // --- bounds, crop, ascent and padding ---------------------------------------------------------------

    @Test
    void aScreenSizedRasterIsOneTileWithTheOldAscentAndHeight() {
        var canvas = GuiArt.capCanvas();
        canvas.fill(0, 0, 176, 114, 0xFF112233);
        GuiArt.Tile tile = onlyTile(canvas);
        assertThat(tile).extracting(GuiArt.Tile::x, GuiArt.Tile::y, GuiArt.Tile::width, GuiArt.Tile::height)
                .containsExactly(0, 0, 176, 114);
        assertThat(tile.ascent()).isEqualTo(13);
        assertThat(tile.cell()).isEqualTo(114);
        assertThat(tile.padding()).isZero();
    }

    @Test
    void aTileIsCroppedToItsInkBoxAndItsAscentFollowsItsTopRow() {
        var canvas = GuiArt.capCanvas();
        canvas.fill(30, 40, 10, 20, 0xFFFFFFFF);
        canvas.blend(60, 41, 0x01000000); // a nearly invisible pixel still counts as ink (alpha > 0)
        GuiArt.Tile tile = onlyTile(canvas);
        assertThat(tile).extracting(GuiArt.Tile::x, GuiArt.Tile::y, GuiArt.Tile::width, GuiArt.Tile::height)
                .containsExactly(30, 40, 31, 20);
        assertThat(tile.ascent()).isEqualTo(13 - 40);
        assertThat(tile.cell()).as("a negative ascent needs no padding: H = th").isEqualTo(20);
        assertThat(tile.argb()).hasSize(31 * 20);
        assertThat(tile.argb()[0]).isEqualTo(0xFFFFFFFF);
        assertThat(tile.argb()[30]).as("the last column of the first row is the faint pixel's column").isZero();
        assertThat(tile.argb()[1 * 31 + 30]).isEqualTo(0x01000000);
    }

    @Test
    void aBannerAboveTheScreenIsPaddedToSatisfyAscentAtMostHeight() {
        // gui.md §11.3 worked example: 120 x 12 at (28, -30): a = 43, H = max(12, 43), 31 transparent rows
        var canvas = GuiArt.capCanvas();
        canvas.fill(28, -30, 120, 12, 0xFF445566);
        GuiArt.Tile tile = onlyTile(canvas);
        assertThat(tile.ascent()).isEqualTo(43);
        assertThat(tile.height()).isEqualTo(12);
        assertThat(tile.cell()).isEqualTo(43);
        assertThat(tile.padding()).isEqualTo(31);
        assertThat(tile.argb()).hasSize(120 * 43);
        assertThat(tile.argb()[12 * 120 - 1]).as("the art's last pixel").isEqualTo(0xFF445566);
        assertThat(tile.argb()[12 * 120]).as("the first padding pixel").isZero();
        assertThat(java.util.Arrays.stream(tile.argb()).skip(12 * 120).allMatch(p -> p == 0)).isTrue();
    }

    @Test
    void theMinimalPaddingIsZeroWhenTheTileReachesRow13AndBelow() {
        // ty + th >= 13 means th >= a: the cell is the art itself
        var canvas = GuiArt.capCanvas();
        canvas.fill(0, 5, 10, 8, 0xFF000001); // ty = 5, th = 8, a = 8: exactly enough
        assertThat(onlyTile(canvas).padding()).isZero();
        var shorter = GuiArt.capCanvas();
        shorter.fill(0, 5, 10, 7, 0xFF000001); // ty + th = 12 < 13
        GuiArt.Tile tile = onlyTile(shorter);
        assertThat(tile.ascent()).isEqualTo(8);
        assertThat(tile.cell()).isEqualTo(8);
        assertThat(tile.padding()).isEqualTo(1);
    }

    @Test
    void theHighestReachableRowIsMinus243WithAnAscentOf256() {
        var canvas = GuiArt.capCanvas();
        canvas.fill(0, -243, 16, 4, 0xFFFFFFFF);
        GuiArt.Tile tile = onlyTile(canvas);
        assertThat(tile.ascent()).isEqualTo(256);
        assertThat(tile.cell()).isEqualTo(256);
        assertThat(tile.padding()).isEqualTo(252);
    }

    // --- the 256-px grid --------------------------------------------------------------------------------

    @Test
    void aRasterOf256By256NeverSplits() {
        var canvas = GuiArt.capCanvas();
        canvas.fill(-100, 100, 256, 256, 0xFFFFFFFF);
        GuiArt.Tile tile = onlyTile(canvas);
        assertThat(tile.width()).isEqualTo(256);
        assertThat(tile.height()).isEqualTo(256);
    }

    @Test
    void a530By530RasterIsNineTilesInRowMajorOrderOnAGridAnchoredAtItsTopLeft() {
        var canvas = GuiArt.capCanvas();
        canvas.fill(-17, -9, 530, 530, 0xFFFFFFFF);
        List<GuiArt.Tile> tiles = GuiArt.cut(canvas);
        assertThat(tiles).hasSize(9);
        assertThat(tiles).extracting(GuiArt.Tile::row).containsExactly(0, 0, 0, 1, 1, 1, 2, 2, 2);
        assertThat(tiles).extracting(GuiArt.Tile::col).containsExactly(0, 1, 2, 0, 1, 2, 0, 1, 2);
        assertThat(tiles).extracting(GuiArt.Tile::x).containsExactly(-17, 239, 495, -17, 239, 495, -17, 239, 495);
        assertThat(tiles).extracting(GuiArt.Tile::y).containsExactly(-9, -9, -9, 247, 247, 247, 503, 503, 503);
        assertThat(tiles).extracting(GuiArt.Tile::width).containsExactly(256, 256, 18, 256, 256, 18, 256, 256, 18);
        assertThat(tiles).extracting(GuiArt.Tile::height).containsExactly(256, 256, 256, 256, 256, 256, 18, 18, 18);
        assertThat(tiles).extracting(GuiArt.Tile::ascent).containsExactly(22, 22, 22, -234, -234, -234, -490, -490, -490);
    }

    @Test
    void aFullyTransparentTileIsNotEmittedAndEachOtherTileKeepsItsGridCoordinates() {
        var canvas = GuiArt.capCanvas();
        canvas.fill(0, 0, 4, 4, 0xFFFFFFFF); // top-left of the bounds
        canvas.fill(576, 576, 4, 4, 0xFFFFFFFF); // bottom-right: the bounds are 580 x 580, three cells each way
        List<GuiArt.Tile> tiles = GuiArt.cut(canvas);
        assertThat(tiles).extracting(t -> t.row() + "/" + t.col()).containsExactly("0/0", "2/2");
        // the second tile is cropped to its own ink, not to its grid cell (512..579)
        assertThat(tiles.get(1)).extracting(GuiArt.Tile::x, GuiArt.Tile::y, GuiArt.Tile::width, GuiArt.Tile::height)
                .containsExactly(576, 576, 4, 4);
    }

    @Test
    void anEmptyCanvasHasNoTiles() {
        assertThat(GuiArt.cut(GuiArt.capCanvas())).isEmpty();
    }

    // --- emit-time invariants -----------------------------------------------------------------------------

    @Test
    void anAscentHigherThanTheHeightIsRefusedBeforeAnyProviderExists() {
        assertThatThrownBy(() -> GuiArt.check(0, 0, 0, 0, 10, 12, 20))
                .isInstanceOf(CompileRejectException.class)
                .hasMessageContaining("ascent 20 is higher than height 12")
                .satisfies(error -> assertThat(((CompileRejectException) error).code())
                        .isEqualTo(DiagnosticCodes.GUI_ART_GEOMETRY_INVALID));
    }

    @Test
    void everyOtherLimitIsRefusedToo() {
        assertThatThrownBy(() -> GuiArt.check(0, 0, 0, 0, 0, 12, 12)).hasMessageContaining("width 0");
        assertThatThrownBy(() -> GuiArt.check(0, 0, 0, 0, 257, 12, 12)).hasMessageContaining("width 257");
        assertThatThrownBy(() -> GuiArt.check(0, 0, 0, 0, 10, 257, 13)).hasMessageContaining("height 257");
        assertThatThrownBy(() -> GuiArt.check(0, 0, 0, -244, 10, 12, 12)).hasMessageContaining("-244");
        GuiArt.check(0, 0, 0, -243, 256, 256, 256); // the extremes themselves are fine
    }

    @Test
    void aCanvasReachingAboveRowMinus243QuarantinesTheGui() {
        // the cap normally prevents this; a canvas of another origin shows the assertion guards the cut itself
        var tooHigh = new ArgbCanvas(0, -300, 64, 64);
        tooHigh.fill(0, -300, 8, 8, 0xFFFFFFFF);
        assertThatThrownBy(() -> GuiArt.cut(tooHigh))
                .isInstanceOf(CompileRejectException.class)
                .satisfies(error -> assertThat(((CompileRejectException) error).code())
                        .isEqualTo(DiagnosticCodes.GUI_ART_GEOMETRY_INVALID));
    }

    // --- the compiler: title pen, providers, assets -------------------------------------------------------

    @SafeVarargs
    private static GuiCompilation compileOne(Map<String, Object>... layers) {
        return compile(new Asked(), layers);
    }

    @Test
    void aFullScreenPanelKeepsTheTitleOfBeforeTheOpenCanvas() {
        GuiCompilation compiled = compileOne(panel("frame", 0, 0, 176, 114));
        assertThat(compiled.model().title()).isEqualTo("<shift:-8><font:shop:gui><#FFFFFF></font><shift:-169>");
        assertThat(compiled.fonts()).hasSize(1);
        assertThat(compiled.fonts().get(0).provider()).containsEntry("ascent", 13).containsEntry("height", 114);
    }

    @Test
    void artThatStartsLeftOfTheScreenShiftsBackFarther() {
        GuiCompilation compiled = compileOne(panel("left", -40, 20, 30, 10));
        // pen 8 -> -40: shift -48; the tile is 30 wide so the pen is -9; back to 8: shift 17
        assertThat(compiled.model().title()).isEqualTo("<shift:-48><font:shop:gui><#FFFFFF></font><shift:17>");
        assertThat(compiled.fonts().get(0).provider()).containsEntry("ascent", -7).containsEntry("height", 10);
        assertThat(compiled.assets().keySet()).containsExactly("assets/shop/textures/font/materione/gui/test.png");
    }

    @Test
    void twoNeighbouring256TilesNeedAShiftOfMinusOneBetweenThem() {
        GuiCompilation compiled = compileOne(panel("wide", 0, 0, 300, 20));
        // tile 0: x 0..255 (pen 257 after), tile 1: x 256..299; the second glyph is drawn at 256 = 257 - 1
        assertThat(compiled.model().title()).isEqualTo("<shift:-8><font:shop:gui><#FFFFFF></font>"
                + "<shift:-1><font:shop:gui><#FFFFFF></font><shift:-293>");
    }

    @Test
    void theNextTileRowBeginsWithTheNegativeShiftOfTheWholeRow() {
        GuiCompilation compiled = compileOne(panel("block", 0, 0, 300, 300));
        // row 0: 256 then 44 wide, ends at pen 301; row 1 starts again at 0: shift -301; its second tile -1 as before
        assertThat(compiled.model().title()).isEqualTo(
                "<shift:-8><font:shop:gui><#FFFFFF></font><shift:-1><font:shop:gui><#FFFFFF></font>"
                        + "<shift:-301><font:shop:gui><#FFFFFF></font><shift:-1><font:shop:gui><#FFFFFF></font>"
                        + "<shift:-293>");
        assertThat(compiled.fonts()).extracting(f -> f.provider().get("height")).containsExactly(256, 256, 44, 44);
        assertThat(compiled.assets().keySet()).containsExactly(
                "assets/shop/textures/font/materione/gui/test.png",
                "assets/shop/textures/font/materione/gui_tile/74657374/0_1.png",
                "assets/shop/textures/font/materione/gui_tile/74657374/1_0.png",
                "assets/shop/textures/font/materione/gui_tile/74657374/1_1.png");
    }

    @Test
    void textFollowsTheArtFromTheOriginWhateverTheArtDid() {
        GuiCompilation compiled = compileOne(panel("left", -40, 20, 30, 10),
                GuiTestSupport.layer("label", "text", "text", "Hi", "x", 8, "y", 6));
        // the art piece returned the pen to 8, so the text at x = 8 needs no shift of its own
        assertThat(compiled.model().title()).endsWith("<shift:17><font:shop:gui/smallcaps_y6><#404040>Hi</font>");
    }

    @Test
    void codepointsAreAskedPerEmittedTileInRowMajorOrderAndForNothingElse() {
        var asked = new Asked();
        compile(asked, panel("big", 0, 0, 300, 300), panel("tiny", 596, 596, 4, 4));
        assertThat(asked.keys).containsExactly("bg/0/0", "bg/0/1", "bg/1/0", "bg/1/1", "bg/2/2");
    }

    // --- nothing painted, nothing emitted ----------------------------------------------------------------

    @Test
    void aGuiThatPaintsNoPixelEmitsNoPngNoProviderNoCodepointAndNoGlyph() {
        var asked = new Asked();
        GuiCompilation compiled = compile(asked);
        assertThat(asked.keys).isEmpty();
        assertThat(compiled.assets()).isEmpty();
        assertThat(compiled.fonts()).isEmpty();
        assertThat(compiled.model().title()).isEmpty();
    }

    @Test
    void aGuiWithOnlyTextAndEmptyLayersEmitsOnlyTheTextFont() {
        var asked = new Asked();
        GuiCompilation compiled = compile(asked, panel("zero", 10, 10, 0, 0),
                GuiTestSupport.layer("label", "text", "text", "Hi", "x", 8, "y", 6));
        assertThat(asked.keys).isEmpty();
        assertThat(compiled.model().title()).isEqualTo("<font:shop:gui/smallcaps_y6><#404040>Hi</font>");
        assertThat(compiled.fonts()).allSatisfy(font -> assertThat(font.fontKey()).startsWith("shop:gui/smallcaps"));
        assertThat(compiled.assets().keySet()).containsExactly("assets/shop/textures/font/materione/smallcaps.png");
    }

    @Test
    void aTransparentLayerAndAFullyTransparentOpacityPaintNothing() {
        var asked = new Asked();
        GuiCompilation compiled = compile(asked, panel("ghost", 0, 0, 40, 40, "opacity", 0));
        assertThat(asked.keys).isEmpty();
        assertThat(compiled.assets()).isEmpty();
    }

    // --- the cap ------------------------------------------------------------------------------------------

    @Test
    void artPastTheCapIsClippedAndReportedOncePerLayerWithoutSkippingTheLayer() {
        var asked = new Asked();
        // x -600..-401 in a cap that starts at -424: 24 columns survive
        GuiCompilation compiled = compile(asked, panel("far", -600, 20, 200, 30));
        assertThat(compiled.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.GUI_ART_CLIPPED);
            assertThat(diagnostic.message()).contains("'far'").contains("-424").contains("599").contains("780");
        });
        assertThat(compiled.layers()).singleElement().satisfies(layer -> assertThat(layer.skipped()).isFalse());
        var png = PngDecoder.decode(compiled.assets().get("assets/shop/textures/font/materione/gui/test.png"))
                .orElseThrow();
        assertThat(png.width()).isEqualTo(24); // x -424..-401
        assertThat(compiled.model().title()).startsWith("<shift:-432>");
    }

    @Test
    void aLayerEntirelyPastTheCapPaintsNothingButIsStillReported() {
        GuiCompilation compiled = compileOne(panel("gone", 700, 900, 50, 50));
        assertThat(compiled.diagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.GUI_ART_CLIPPED));
        assertThat(compiled.assets()).isEmpty();
        assertThat(compiled.fonts()).isEmpty();
    }

    @Test
    void theCapEdgesThemselvesAreReachableAndNotReported() {
        GuiCompilation compiled = compileOne(panel("corner", -424, -243, 1024, 1024));
        assertThat(compiled.diagnostics()).isEmpty();
        assertThat(compiled.fonts()).hasSize(16);
        assertThat(compiled.fonts()).allSatisfy(font -> {
            var provider = font.provider();
            assertThat((int) provider.get("ascent")).isLessThanOrEqualTo((int) provider.get("height"));
            assertThat((int) provider.get("height")).isBetween(1, 256);
        });
        assertThat(compiled.fonts().stream().map(font -> font.provider().get("ascent")).distinct().toList())
                .containsExactly(256, 0, -256, -512);
    }

    @Test
    void thePositionsOfPanelsButtonsAndImagesReachTheWholeCap() {
        var type = GuiTestSupport.type();
        var gui = GuiTestSupport.parse(type, GuiTestSupport.definition(1,
                panel("right", 599, 780, 1, 1),
                GuiTestSupport.layer("button", "button", "x", -424, "y", -243, "width", 1024, "height", 1024)));
        assertThat(gui.skipped()).isEmpty();
        var tooFar = GuiTestSupport.parse(type, GuiTestSupport.definition(1,
                panel("lost", 1025, 0, 10, 10)));
        assertThat(tooFar.skipped()).singleElement()
                .satisfies(skip -> assertThat(skip.reason()).contains("x").contains("1024"));
    }

    // --- the golden: a padded tile --------------------------------------------------------------------------

    @Test
    void aBannerAboveTheScreenCompilesToItsGoldenPaddedTile() throws IOException {
        // the frame starts more than 256 px below the banner, so the two are separate tiles of one raster
        var definition = GuiTestSupport.definition(1, panel("banner", 28, -30, 120, 12),
                panel("frame", 0, 230, 176, 100));
        var gui = (GuiDefinition) GuiTestSupport.type().parse(BANNER, definition, Map.of()).orElseThrow();
        GuiCompilation compiled = GuiCompiler.compile(gui, TextureSource.NONE, new Asked());
        assertThat(compiled.diagnostics()).isEmpty();

        var out = new StringBuilder();
        out.append("title: ").append(compiled.model().title().replace("", "{E200}").replace("", "{E201}"))
                .append('\n');
        compiled.fonts().forEach(font -> out.append("font ").append(font.fontKey()).append(' ')
                .append(font.provider().toString().replace("", "{E200}").replace("", "{E201}"))
                .append('\n'));
        compiled.assets().forEach((path, bytes) -> {
            var decoded = PngDecoder.decode(bytes).orElseThrow();
            out.append("asset ").append(path).append(' ').append(decoded.width()).append('x').append(decoded.height())
                    .append(' ').append(sha256(bytes)).append('\n');
        });
        Path summary = temp.resolve("summary.txt");
        Files.writeString(summary, out.toString(), StandardCharsets.UTF_8);
        if (GoldenFiles.regenEnabled()) {
            GoldenFiles.writeGolden(summary, "gui/open-canvas-banner.txt");
        }
        GoldenFiles.assertMatchesGolden(summary, "gui/open-canvas-banner.txt");

        // the padded tile pinned by pixels: ty + th = -18 < 13, so 120 x 43 with the art in the first 12 rows
        assertThat(compiled.assets().keySet()).hasSize(2);
        var assetsByWidth = compiled.fonts().stream()
                .filter(font -> font.provider().get("ascent").equals(43)).toList();
        assertThat(assetsByWidth).singleElement().satisfies(font ->
                assertThat(font.provider()).containsEntry("height", 43));
        Path png = temp.resolve("banner.png");
        byte[] padded = compiled.assets().values().stream()
                .filter(bytes -> PngDecoder.decode(bytes).orElseThrow().height() == 43).findFirst().orElseThrow();
        Files.write(png, padded);
        if (GoldenFiles.regenEnabled()) {
            GoldenFiles.writeGolden(png, "gui/open-canvas-banner-tile.png");
        }
        GoldenFiles.assertMatchesGolden(png, "gui/open-canvas-banner-tile.png");
        var pixels = PngDecoder.decode(padded).orElseThrow();
        assertThat(pixels.width()).isEqualTo(120);
        assertThat(pixels.argb()[11 * 120 + 60]).as("last art row").isNotZero();
        assertThat(pixels.argb()[12 * 120 + 60]).as("first padding row").isZero();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // --- dynamic overlays ---------------------------------------------------------------------------------

    @Test
    void anOverlayGlyphIsCutPerFrameAndAsksForCodepointsWithLayerAndFrameBeforeTheTile() {
        var asked = new Asked();
        var type = GuiTestSupport.type();
        var gui = GuiTestSupport.parse(type, GuiTestSupport.definition(1,
                panel("tab", -20, 30, 300, 12, "visible_if", true),
                panel("empty", 10, 10, 0, 0, "visible_if", true)));
        GuiCompilation compiled = GuiCompiler.compile(gui, TextureSource.NONE, asked);
        assertThat(compiled.diagnostics()).isEmpty();
        // the static background is blank: no allocation for it; the tab has two tiles (256 + 44 wide), the empty
        // layer none
        assertThat(asked.keys).containsExactly("tab#0/0/0", "tab#0/0/1");
        var pieces = compiled.model().visuals().pieces();
        var tab = (GuiVisualPlan.Raster) pieces.get(0);
        assertThat(tab.frame(0).tiles()).extracting(GuiTitle.ArtGlyph::x, GuiTitle.ArtGlyph::inkWidth)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(-20, 256), org.assertj.core.groups.Tuple.tuple(236, 44));
        var empty = (GuiVisualPlan.Raster) pieces.get(1);
        assertThat(empty.frame(0).tiles()).as("a frame that paints nothing adds nothing").isEmpty();
        assertThat(compiled.assets()).containsOnlyKeys(
                "assets/shop/textures/font/materione/gui_overlay/74657374/746162/0.png",
                "assets/shop/textures/font/materione/gui_overlay/74657374/746162/0/0_1.png");
        assertThat(compiled.model().title()).as("a dynamic GUI starts with an empty title").isEmpty();
    }

    @Test
    void anOverlayPastTheCapIsReportedOnceEvenWithManyFrames() {
        var gui = GuiTestSupport.parse(GuiTestSupport.type(), GuiTestSupport.definition(1,
                panel("anim", -500, 0, 100, 10, "materione:overlay_frames",
                        Map.of("every", 2, "layers", List.of(Map.of("x", -500), Map.of("x", -490), Map.of("x", -480))))));
        GuiCompilation compiled = GuiCompiler.compile(gui, TextureSource.NONE, new Asked());
        assertThat(compiled.diagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.GUI_ART_CLIPPED));
        assertThat(((GuiVisualPlan.Raster) compiled.model().visuals().pieces().get(0)).frames()).hasSize(3);
    }

    // --- clickable art outside the container (gui.md §11.4) -------------------------------------------------

    private static List<dev.materione.api.diagnostics.Diagnostic> diagnose(Map<String, Object> definition) {
        var type = GuiTestSupport.type();
        return type.diagnose(type.parse(NamespacedId.of("shop", "test"), definition, Map.of()).orElseThrow());
    }

    private static Map<String, Object> clickableGui(Map<String, Object> layer, Object... behavior) {
        var definition = GuiTestSupport.definition(1, layer, GuiTestSupport.layer("note", "text", "text", "Hi",
                "x", 8, "y", 6));
        if (behavior.length > 0) {
            definition.put("behavior", List.of(behavior));
        }
        return definition;
    }

    @Test
    void aButtonOutsideTheContainerIsWarnedAboutAndStillDrawn() {
        var button = GuiTestSupport.layer("away", "button", "x", 200, "y", 20, "width", 40, "height", 20);
        var diagnostics = diagnose(clickableGui(button));
        assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.GUI_CLICK_OUTSIDE_CONTAINER);
            assertThat(diagnostic.message()).contains("'away'").contains("decoration only");
        });
        assertThat(compile(new Asked(), button).fonts()).as("the layer is kept and drawn").hasSize(1);
    }

    @Test
    void aButtonOverASlotIsNotWarnedAbout() {
        // a slot's interior centre is (16 + 18 * col, 26 + 18 * row): the button covers slot 0's centre
        var button = GuiTestSupport.layer("ok", "button", "x", 8, "y", 18, "width", 20, "height", 20);
        assertThat(diagnose(clickableGui(button))).isEmpty();
    }

    @Test
    void anyLayerAGuiClickRuleNamesIsJudgedToo() {
        var far = GuiTestSupport.layer("far", "image", "image", "gui/x", "x", -100, "y", 20, "width", 40, "height", 20);
        var rule = Map.of("on", "gui.click", "layer", "far", "do", List.of(Map.of("gui.close", Map.of())));
        assertThat(diagnose(clickableGui(far, rule))).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.GUI_CLICK_OUTSIDE_CONTAINER));
        assertThat(diagnose(clickableGui(far))).as("with no rule it is plain art").isEmpty();
        var inside = GuiTestSupport.layer("far", "image", "image", "gui/x", "x", 8, "y", 18, "width", 40, "height", 20);
        assertThat(diagnose(clickableGui(inside, rule))).isEmpty();
    }

    // --- the QA fixture pack (fixtures/packs/opencanvas) ---------------------------------------------------

    private static GuiCompilation compileFixture(String id) throws IOException {
        var raw = dev.materione.core.document.LosslessYaml.parse(Files.readString(GoldenFiles.fixturesRoot()
                .resolve("packs").resolve("opencanvas").resolve("guis").resolve(id + ".yml"))).data();
        @SuppressWarnings("unchecked")
        var fields = (Map<String, Object>) raw.get(id);
        var gui = (GuiDefinition) GuiTestSupport.type()
                .parse(NamespacedId.of("opencanvas", id), fields, Map.of()).orElseThrow();
        assertThat(gui.skipped()).isEmpty();
        return GuiCompiler.compile(gui, TextureSource.NONE, new Asked());
    }

    @Test
    void theOffscreenQaGuiPutsArtOnEverySideOfTheScreenAndNeedsAPaddedTile() throws IOException {
        GuiCompilation compiled = compileFixture("offscreen");
        assertThat(compiled.diagnostics()).isEmpty();
        assertThat(compiled.fonts()).isNotEmpty().allSatisfy(font -> {
            var provider = font.provider();
            assertThat((int) provider.get("ascent")).isLessThanOrEqualTo((int) provider.get("height"));
        });
        // the ceiling (-243) and the panel above the screen (-50..-21) make tile 0/0 of the raster: it ends above
        // row 13, so its 223 art rows are padded to the 256-row cell the ascent of 256 requires
        var padded = compiled.fonts().stream().filter(font -> font.provider().get("ascent").equals(256))
                .map(font -> compiled.assets().get("assets/opencanvas/textures/font/materione/gui/offscreen.png"))
                .findFirst().orElseThrow();
        var pixels = PngDecoder.decode(padded).orElseThrow();
        assertThat(pixels.height()).isEqualTo(256);
        assertThat(java.util.Arrays.stream(pixels.argb(), 255 * pixels.width(), 256 * pixels.width())
                .allMatch(pixel -> pixel == 0)).as("the last row is padding").isTrue();
        assertThat(java.util.Arrays.stream(pixels.argb(), 222 * pixels.width(), 223 * pixels.width())
                .anyMatch(pixel -> pixel != 0)).as("art row 222 (y -21) is the last inked row").isTrue();
    }

    @Test
    void thePlainQaGuiEmitsNothingAtAll() throws IOException {
        GuiCompilation compiled = compileFixture("plain");
        assertThat(compiled.assets()).isEmpty();
        assertThat(compiled.fonts()).isEmpty();
        assertThat(compiled.model().title()).isEmpty();
        assertThat(compiled.model().accessibleTitle()).isEqualTo("Plain chest");
    }

    // --- keys -----------------------------------------------------------------------------------------------

    @Test
    void theFirstTileKeepsTheGuisOwnAllocationAndOtherTilesGetDistinctKeys() {
        NamespacedId gui = NamespacedId.of("shop", "main/menu");
        assertThat(GuiOverlayKeys.background(gui, 0, 0)).as("existing GUIs keep their codepoint").isEqualTo(gui);
        assertThat(GuiOverlayKeys.background(gui, 0, 1)).isNotEqualTo(GuiOverlayKeys.background(gui, 1, 0))
                .isNotEqualTo(gui);
        assertThat(GuiOverlayKeys.allocation(gui, "tab", 2, 0, 0)).isEqualTo(GuiOverlayKeys.allocation(gui, "tab", 2));
        assertThat(GuiOverlayKeys.allocation(gui, "tab", 2, 1, 3).toString())
                .startsWith(GuiOverlayKeys.allocation(gui, "tab", 2).toString()).endsWith("/tile/1/3");
        assertThat(GuiOverlayKeys.backgroundTexture(gui, 0, 0)).isEqualTo("font/materione/gui/main/menu");
        assertThat(GuiOverlayKeys.backgroundTexture(gui, 2, 1)).startsWith("font/materione/gui_tile/").endsWith("/2_1");
    }
}
