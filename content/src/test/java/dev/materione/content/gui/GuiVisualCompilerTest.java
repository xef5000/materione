package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.gui.TextureSource;
import dev.materione.core.texture.PngDecoder;
import dev.materione.core.texture.PackTextures;
import dev.materione.testing.golden.GoldenFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiVisualCompilerTest {
    @TempDir
    Path temp;

    @Test
    void overlayPackOutputMatchesGolden() throws Exception {
        var fixture = GoldenFiles.fixturesRoot().resolve("packs").resolve("overlay");
        var raw = dev.materione.core.document.LosslessYaml.parse(
                Files.readString(fixture.resolve("guis").resolve("tabs.yml"))).data();
        @SuppressWarnings("unchecked")
        var fields = (Map<String, Object>) raw.get("tabs");
        var gui = (GuiDefinition) GuiTestSupport.type()
                .parse(dev.materione.api.NamespacedId.of("overlay", "tabs"), fields, Map.of()).orElseThrow();
        var next = new AtomicInteger(0xE201);
        var result = GuiCompiler.compile(gui, 0xE200, new PackTextures("overlay", fixture),
                (layer, frame) -> next.getAndIncrement());
        assertThat(result.diagnostics()).isEmpty();
        var output = new StringBuilder();
        new java.util.TreeMap<>(result.assets()).forEach((path, bytes) ->
                output.append("asset ").append(path).append(' ').append(hash(bytes)).append('\n'));
        result.fonts().forEach(font -> output.append("font ").append(font.fontKey()).append(' ')
                .append(hash(font.provider().toString().getBytes(StandardCharsets.UTF_8))).append('\n'));
        for (var piece : result.model().visuals().pieces()) {
            output.append("piece ").append(piece.order()).append(' ').append(piece.layer()).append(' ')
                    .append(piece.getClass().getSimpleName()).append('\n');
        }
        Path actual = temp.resolve("overlays.txt");
        Files.writeString(actual, output.toString(), StandardCharsets.UTF_8);
        if (GoldenFiles.regenEnabled()) {
            GoldenFiles.writeGolden(actual, "gui/overlays-compiled.txt");
        }
        GoldenFiles.assertMatchesGolden(actual, "gui/overlays-compiled.txt");
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void dynamicPiecesUseRasterThenTextOrderAndBackgroundExcludesEveryOverlay() {
        var gui = GuiTestSupport.parse(GuiTestSupport.type(), GuiTestSupport.definition(1,
                GuiTestSupport.layer("lower", "panel", "x", 0, "y", 0, "width", 32, "height", 32),
                GuiTestSupport.layer("label", "text", "x", 8, "y", 6, "text", "Hi ${player.name}",
                        "visible_if", "player.level > 1"),
                GuiTestSupport.layer("upper", "panel", "x", 8, "y", 18, "width", 20, "height", 20,
                        "visible_if", "player.level > 1")));
        var next = new AtomicInteger(0xE201);
        var result = GuiCompiler.compile(gui, 0xE200, TextureSource.NONE, (layer, frame) -> next.getAndIncrement());
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.model().visuals().pieces()).extracting(GuiVisualPlan.Piece::layer)
                .containsExactly("lower", "upper", "label");
        assertThat(result.model().visuals().pieces().get(0)).isInstanceOf(GuiVisualPlan.Raster.class);
        assertThat(result.model().visuals().pieces().get(1)).isInstanceOf(GuiVisualPlan.Raster.class);
        assertThat(result.model().visuals().pieces().get(2)).isInstanceOf(GuiVisualPlan.Text.class);
        assertThat(result.assets()).containsKeys(
                "assets/shop/textures/font/materione/gui/test.png",
                "assets/shop/textures/font/materione/gui_overlay/74657374/6c6f776572/0.png",
                "assets/shop/textures/font/materione/gui_overlay/74657374/7570706572/0.png");
        var background = PngDecoder.decode(result.assets().get(
                "assets/shop/textures/font/materione/gui/test.png")).orElseThrow();
        assertThat(background.argb()).containsOnly(0);
        assertThat(result.fonts().stream().filter(font -> font.fontKey().equals("shop:gui")).count()).isEqualTo(3);
    }

    @Test
    void rasterFramesAreTypedAndInvalidShapesAreQuarantined() {
        var valid = GuiTestSupport.parse(GuiTestSupport.type(), GuiTestSupport.definition(1,
                GuiTestSupport.layer("anim", "panel", "width", 20, "height", 20,
                        "materione:overlay_frames", Map.of("every", 4, "layers", List.of(Map.of("x", 1), Map.of("x", 7))))));
        assertThat(valid.skipped()).isEmpty();
        var result = GuiCompiler.compile(valid, 0xE200, TextureSource.NONE,
                (layer, frame) -> 0xE201 + frame);
        var animation = (GuiVisualPlan.Raster) result.model().visuals().pieces().get(0);
        assertThat(animation.frame(0).codepoint()).isEqualTo(0xE201);
        assertThat(animation.frame(4).codepoint()).isEqualTo(0xE202);
        assertThat(animation.frame(8).codepoint()).isEqualTo(0xE201);
        var invalid = GuiTestSupport.parse(GuiTestSupport.type(), GuiTestSupport.definition(1,
                GuiTestSupport.layer("bad", "panel", "width", 20, "height", 20,
                        "materione:overlay_frames", Map.of("every", 0, "layers", List.of(Map.of())))));
        assertThat(invalid.skipped()).singleElement().satisfies(skip -> assertThat(skip.reason()).contains("frames"));
    }
}
