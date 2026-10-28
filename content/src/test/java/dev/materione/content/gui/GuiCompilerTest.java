package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.AllocatedRange;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.CompileRejectException;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.gui.TextureSource;
import dev.materione.core.texture.PackTextures;
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
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The GUI compiler (gui.md §4): the shop fixture's pack output and background are pinned by
 * goldens; the title's shift arithmetic and every per-layer skip are checked on small GUIs.
 */
class GuiCompilerTest {

    /** The background codepoint of the first GUI in a pack: allocation 1 of U+E200. */
    private static final int FIRST_BACKGROUND = 0xE200;

    private static final Path FIXTURE_PACK = GoldenFiles.fixturesRoot().resolve("gui").resolve("pack");

    private static final NamespacedId SHOP = NamespacedId.of("shop", "shop");

    @TempDir
    Path temp;

    @Test
    void shopCompilesToItsGoldenPackOutputAndTitle() throws IOException {
        GuiType type = GuiTestSupport.type();
        GuiDefinition gui = (GuiDefinition) type.parse(SHOP, GuiTestSupport.golden("shop.yml", "shop"), Map.of())
                .orElseThrow();
        TextureSource textures = new PackTextures("shop", FIXTURE_PACK);

        var recording = new Recording(textures);
        type.compile(gui, NamespacedId.of("shop", "shop_model"), recording, allocations(1));
        assertThat(recording.diagnostics).isEmpty();

        // the title: background from pen −8 with its advance, shifted back, then the heading at (8, 6)
        var compiled = GuiCompiler.compile(gui, FIRST_BACKGROUND, textures);
        assertThat(compiled.model().title()).isEqualTo(
                "<shift:-8><font:shop:gui><#FFFFFF>\uE200</font><shift:-169>"
                        + "<font:shop:gui/smallcaps_y6><#404040>Shop</font>");
        assertThat(compiled.model().accessibleTitle()).isEqualTo("Shop");
        assertThat(compiled.model().rows()).isEqualTo(6);
        assertThat(compiled.model().slots()).containsExactly(
                new dev.materione.content.gui.CompiledGui.Slot(
                        13, NamespacedId.parse("shop:gui/main/sword"), 1));

        // the background texture and the smallcaps sheet, as the pack emits them
        byte[] background = recording.assets.get("assets/shop/textures/font/materione/gui/shop.png");
        assertThat(background).isNotNull();
        assertThat(recording.assets).containsKey("assets/shop/textures/font/materione/smallcaps.png");
        var decoded = PngDecoder.decode(background).orElseThrow();
        assertThat(decoded.width()).isEqualTo(176);
        assertThat(decoded.height()).isEqualTo(222);

        // every font provider the GUI needs: the background glyph, then the heading's offset font
        assertThat(recording.fonts).anySatisfy(line -> assertThat(line).startsWith("shop:gui "));
        assertThat(recording.fonts).anySatisfy(line -> assertThat(line).startsWith("shop:gui/smallcaps_y6 "));
        assertThat(recording.fonts).anySatisfy(line -> assertThat(line)
                .contains("ascent=7").contains("height=16"));

        // goldens: the pack output summary, and the background PNG by bytes and by pixels
        Path summary = temp.resolve("summary.txt");
        Files.writeString(summary, summary(recording), StandardCharsets.UTF_8);
        Path png = temp.resolve("background.png");
        Files.write(png, background);
        if (GoldenFiles.regenEnabled()) {
            GoldenFiles.writeGolden(summary, "gui/shop-compiled.txt");
            GoldenFiles.writeGolden(png, "gui/shop-background.png");
        }
        GoldenFiles.assertMatchesGolden(summary, "gui/shop-compiled.txt");
        GoldenFiles.assertMatchesGolden(png, "gui/shop-background.png");
        var golden = PngDecoder.decode(Files.readAllBytes(GoldenFiles.golden("gui/shop-background.png")))
                .orElseThrow();
        assertThat(decoded.argb()).as("background pixels").containsExactly(golden.argb());
    }

    @Test
    void twoSmallcapsRunsAreSeparatedByTheMeasuredAdvance() {
        var layers = GuiTestSupport.definition(1,
                GuiTestSupport.layer("first", "text", "text", "Hi", "x", 8, "y", 6),
                GuiTestSupport.layer("second", "text", "text", "Yo", "x", 40, "y", 6));
        var compiled = compile(layers, FIRST_BACKGROUND, TextureSource.NONE);
        int hi = SmallcapsFont.measure("Hi");
        assertThat(compiled.diagnostics()).isEmpty();
        assertThat(compiled.model().title()).contains("<shift:" + (32 - hi) + "><font:shop:gui/smallcaps_y6>");
    }

    @Test
    void aDefaultRunLeavesTheNextRunUnplaceableAndSaysWhy() {
        var layers = GuiTestSupport.definition(1,
                GuiTestSupport.layer("plain", "text", "text", "Hi", "x", 8, "y", 6, "font", "default"),
                GuiTestSupport.layer("after", "text", "text", "Yo", "x", 40, "y", 6));
        var compiled = compile(layers, FIRST_BACKGROUND, TextureSource.NONE);
        assertThat(compiled.model().title()).contains("Hi</font>").doesNotContain("Yo");
        assertThat(compiled.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.GUI_LAYER_SKIPPED);
            assertThat(diagnostic.message()).contains("'after'").contains("default-font run");
        });
    }

    @Test
    void aDefaultRunAboveRowFiveIsSkippedBecauseItsGlyphsAreEightPixelsHigh() {
        var layers = GuiTestSupport.definition(1,
                GuiTestSupport.layer("high", "text", "text", "Hi", "x", 8, "y", 4, "font", "default"));
        var compiled = compile(layers, FIRST_BACKGROUND, TextureSource.NONE);
        assertThat(compiled.model().title()).doesNotContain("Hi");
        assertThat(compiled.diagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.message()).contains("row 5 or lower"));
    }

    @Test
    void aMissingTextureSkipsOnlyItsLayer() {
        var layers = GuiTestSupport.definition(1,
                GuiTestSupport.layer("frame", "panel", "x", 0, "y", 0, "width", 176, "height", 114),
                GuiTestSupport.layer("banner", "image", "image", "gui/missing", "x", 4, "y", 4));
        var compiled = compile(layers, FIRST_BACKGROUND, TextureSource.NONE);
        assertThat(compiled.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.GUI_LAYER_SKIPPED);
            assertThat(diagnostic.message()).contains("'banner'").contains("gui/missing");
        });
        assertThat(compiled.model().title()).contains("\uE200");
    }

    @Test
    void textWithReservedCharactersIsSkipped() {
        var layers = GuiTestSupport.definition(1,
                GuiTestSupport.layer("markup", "text", "text", "a<b", "x", 8, "y", 6));
        var compiled = compile(layers, FIRST_BACKGROUND, TextureSource.NONE);
        assertThat(compiled.model().title()).doesNotContain("a<b");
        assertThat(compiled.diagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.message()).contains("may not contain"));
    }

    @Test
    void aLaterItemInTheSameSlotWinsAndSaysSo() {
        var layers = GuiTestSupport.definition(1,
                GuiTestSupport.layer("first", "item", "slot", 4, "item", "minecraft:stone"),
                GuiTestSupport.layer("second", "item", "slot", 4, "item", "minecraft:diamond_sword"));
        var compiled = compile(layers, FIRST_BACKGROUND, TextureSource.NONE);
        assertThat(compiled.model().slots()).singleElement()
                .satisfies(slot -> assertThat(slot.item()).isEqualTo(NamespacedId.parse("minecraft:diamond_sword")));
        assertThat(compiled.diagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.message()).contains("'first'"));
    }

    @Test
    void theBackgroundBlockUsedUpQuarantinesOnlyThatGui() {
        var layers = GuiTestSupport.definition(1,
                GuiTestSupport.layer("frame", "panel", "x", 0, "y", 0, "width", 176, "height", 114));
        GuiDefinition gui = GuiTestSupport.parse(GuiTestSupport.type(), layers);
        var recording = new Recording(TextureSource.NONE);
        assertThatThrownBy(() -> GuiTestSupport.type().compile(gui, NamespacedId.of("test", "t"), recording,
                allocations(5889)))
                .isInstanceOf(CompileRejectException.class)
                .satisfies(error -> assertThat(((CompileRejectException) error).code())
                        .isEqualTo(DiagnosticCodes.GUI_BACKGROUND_EXHAUSTED));
    }

    @Test
    void theLastBackgroundCodepointIsInsideTheBlock() {
        assertThat(GuiBackgrounds.codepoint(1)).isEqualTo(0xE200);
        assertThat(GuiBackgrounds.codepoint(5888)).isEqualTo(0xF8FF);
        assertThat(GuiBackgrounds.fits(5889)).isFalse();
    }

    // --- helpers ---------------------------------------------------------------

    private static GuiCompilation compile(Map<String, Object> layers, int codepoint, TextureSource textures) {
        return GuiCompiler.compile(GuiTestSupport.parse(GuiTestSupport.type(), layers), codepoint, textures);
    }

    /** Allocations that hand out {@code backgroundNumber} for every GUI background ask. */
    private static Allocations allocations(int backgroundNumber) {
        return new Allocations() {
            @Override
            public String allocate(NamespacedId resource, String kind) {
                return "1";
            }

            @Override
            public AllocatedRange range(NamespacedId resource, String kind, int count) {
                return new AllocatedRange(backgroundNumber, count);
            }

            @Override
            public Optional<String> lookup(NamespacedId resource, String kind) {
                return Optional.empty();
            }
        };
    }

    /** The pack output of one compile, recorded in order: what the pack compiler would emit. */
    private static final class Recording implements PackEmitter {

        final TreeMap<String, byte[]> assets = new TreeMap<>();
        final List<String> fonts = new ArrayList<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        private final TextureSource textures;

        Recording(TextureSource textures) {
            this.textures = textures;
        }

        @Override
        public void itemDefinition(NamespacedId id, Map<String, Object> itemModelJson) {}

        @Override
        public void model(NamespacedId id, Map<String, Object> modelJson) {}

        @Override
        public void copyAsset(Path sourceFile, String destination) {}

        @Override
        public void vanillaFile(String path, Map<String, Object> json) {}

        @Override
        public void description(String text) {}

        @Override
        public void generatedAsset(String destination, byte[] bytes) {
            assets.put(destination, bytes.clone());
        }

        @Override
        public void fontProvider(String fontKey, Map<String, Object> providerJson) {
            fonts.add(fontKey + " " + providerJson);
        }

        @Override
        public TextureSource textures() {
            return textures;
        }

        @Override
        public void diagnostic(Diagnostic diagnostic) {
            diagnostics.add(diagnostic);
        }
    }

    /** The golden summary: one line per fact, sorted where the pack compiler sorts. */
    private static String summary(Recording recording) {
        var out = new StringBuilder();
        out.append("diagnostics: ").append(recording.diagnostics.size()).append('\n');
        out.append("fonts:\n");
        for (String font : recording.fonts) {
            out.append("  ").append(font.replace("\u0000", "\\u0000")).append('\n');
        }
        out.append("assets:\n");
        recording.assets.forEach((path, bytes) -> {
            var decoded = PngDecoder.decode(bytes).orElseThrow();
            out.append("  ").append(path).append(' ').append(decoded.width()).append('x')
                    .append(decoded.height()).append(' ').append(sha256(bytes)).append('\n');
        });
        return out.toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
