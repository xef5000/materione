package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.gui.GuiContainer;
import dev.materione.api.gui.TextureSource;
import dev.materione.core.texture.PngDecoder;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The profile-driven compiler for the containers beyond the chests (gui.md §12.5, row C): a hopper and a dispenser
 * compile through the same code as a chest, the slot table, click map, title origin, trailing shift and art cap all
 * coming from the profile. Their pack output is pinned by goldens ({@code gui/hopper-compiled.txt},
 * {@code gui/dispenser-compiled.txt}); the arithmetic they rest on is asserted beside them.
 */
class GuiContainerCompilerTest {

    /** The private-use codepoints the compiler hands out for art, masked in the goldens so they read as text. */
    private static final String PRIVATE_USE = "[\\uE000-\\uF8FF]";

    @TempDir
    Path temp;

    /** Codepoints handed out in the order the compiler asks. */
    private static final class Asked implements GuiCodepoints {
        private int next = 0xE200;

        @Override
        public int codepoint(String layer, int frame, int row, int col) {
            return next++;
        }
    }

    private static Map<String, Object> panel(String id, int x, int y, int width, int height) {
        return GuiTestSupport.layer(id, "panel", "x", x, "y", y, "width", width, "height", height,
                "corners", "vanilla");
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    private static GuiDefinition gui(String container, Map<String, Object>... layers) {
        var definition = new LinkedHashMap<String, Object>();
        definition.put("title", "Test");
        definition.put("container", container);
        definition.put("layers", new ArrayList<Object>(List.of(layers)));
        return GuiTestSupport.parse(GuiTestSupport.type(), definition);
    }

    /** The hopper of the golden: frame, banner above the screen, wells, a heading, an item and a button. */
    private static GuiDefinition hopper() {
        return gui("hopper",
                panel("frame", 0, 0, 176, 133),
                panel("banner", 28, -30, 120, 12),
                GuiTestSupport.layer("wells", "slots", "region", "all"),
                GuiTestSupport.layer("title", "text", "text", "Orders", "x", 8, "y", 6),
                GuiTestSupport.layer("sword", "item", "slot", 0, "item", "minecraft:diamond_sword"),
                GuiTestSupport.layer("tab", "button", "x", 80, "y", 20, "width", 40, "height", 16));
    }

    /** The dispenser of the golden: the same, with art hanging off the left edge. */
    private static GuiDefinition dispenser() {
        return gui("dispenser",
                panel("frame", 0, 0, 176, 166),
                panel("flag", -40, 100, 60, 20),
                GuiTestSupport.layer("wells", "slots", "region", "all"),
                GuiTestSupport.layer("title", "text", "text", "Drops", "x", 8, "y", 6),
                GuiTestSupport.layer("gem", "item", "slot", 4, "item", "minecraft:emerald"),
                GuiTestSupport.layer("tab", "button", "x", 80, "y", 36, "width", 32, "height", 34));
    }

    // --- the hopper -------------------------------------------------------------------------------------

    @Test
    void aHopperCompilesToItsGolden() throws IOException {
        GuiCompilation compiled = GuiCompiler.compile(hopper(), TextureSource.NONE, new Asked());
        assertThat(compiled.diagnostics()).isEmpty();
        assertThat(compiled.model().container().id()).isEqualTo("hopper");
        pin(compiled, "gui/hopper-compiled.txt");

        // the hopper's title is not centred: no trailing shift, the pen ends where its last piece ended
        assertThat(compiled.model().title()).doesNotMatch(".*<shift:-?[0-9]+>");
        // five slots, the click map reaches exactly them: the button covers the slots whose centres it contains
        assertThat(compiled.model().clicks().layersAt(2)).contains("tab");
        assertThat(compiled.model().clicks().layersAt(3)).contains("tab");
        assertThat(compiled.model().clicks().layersAt(4)).doesNotContain("tab");
        assertThat(compiled.model().slots()).singleElement().satisfies(slot -> assertThat(slot.slot()).isZero());
    }

    // --- the dispenser ------------------------------------------------------------------------------------

    @Test
    void aDispenserCompilesToItsGoldenWithTheTrailingShift() throws IOException {
        GuiCompilation compiled = GuiCompiler.compile(dispenser(), TextureSource.NONE, new Asked());
        assertThat(compiled.diagnostics()).isEmpty();
        pin(compiled, "gui/dispenser-compiled.txt");

        // a centred title ends at origin + imageWidth - 16 = 168: the glyph advances sum to 160 (gui.md §12.5.3)
        assertThat(penAtTheEndOf(compiled.model().title(), compiled)).isEqualTo(168);
        assertThat(compiled.model().title()).matches(".*<shift:-?[0-9]+>");
    }

    @Test
    void theDispenserSlotsAreItsOwnThreeByThreeGrid() {
        GuiCompilation compiled = GuiCompiler.compile(dispenser(), TextureSource.NONE, new Asked());
        // slot centres are (70|88|106, 25|43|61); the button [80,112) x [36,70) takes columns 1 and 2 of rows 1 and 2
        assertThat(compiled.model().clicks().slotsOf("tab")).containsExactly(4, 5, 7, 8);
        assertThat(compiled.model().clicks().slotsOf("gem")).containsExactly(4);
        assertThat(compiled.model().slots()).singleElement().satisfies(slot -> assertThat(slot.slot()).isEqualTo(4));
    }

    @Test
    void aDispenserWithNoArtAndNoTextKeepsItsPlainTitleForTheClientToCentre() {
        GuiCompilation compiled = GuiCompiler.compile(gui("dispenser"), TextureSource.NONE, new Asked());
        assertThat(compiled.model().title()).isEmpty();
        assertThat(compiled.model().accessibleTitle()).isEqualTo("Test");
    }

    // --- slots outside the container --------------------------------------------------------------------

    @Test
    void anItemOutsideTheContainerIsSkippedWithTheContainersOwnSlotCount() {
        var type = GuiTestSupport.type();
        var definition = new LinkedHashMap<String, Object>();
        definition.put("title", "Test");
        definition.put("container", "hopper");
        definition.put("layers", new ArrayList<Object>(List.of(
                GuiTestSupport.layer("far", "item", "slot", 5, "item", "minecraft:stone"))));
        GuiDefinition parsed = GuiTestSupport.parse(type, definition);
        assertThat(parsed.skipped()).singleElement().satisfies(skip -> assertThat(skip.reason())
                .contains("outside the GUI's container").contains("slots 0 to 4"));
    }

    // --- the art cap -----------------------------------------------------------------------------------------

    @Test
    void everyProfileHasTheCapOfTheSharedScreenWidthAndTitleRow() {
        for (GuiContainer container : GuiContainers.all()) {
            GuiArt.Cap cap = GuiArt.Cap.of(container);
            // 176-px screens and title row 6 share one box: x -424..599, y -243..780 (gui.md §11.2, §12.5.4)
            assertThat(cap).as(container.id()).isEqualTo(new GuiArt.Cap(-424, -243));
            assertThat(cap.right()).isEqualTo(599);
            assertThat(cap.bottom()).isEqualTo(780);
        }
    }

    @Test
    void aHopperPaintingPastTheCapReportsTheCapOfItsProfile() {
        // x -400 + 1024 reaches column 623, past the cap's last column 599 (MTR-0090 names the profile's box)
        GuiCompilation compiled = GuiCompiler.compile(
                gui("hopper", panel("wide", -400, 0, 1024, 20)), TextureSource.NONE, new Asked());
        assertThat(compiled.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(dev.materione.api.diagnostics.DiagnosticCodes.GUI_ART_CLIPPED);
            assertThat(diagnostic.message()).contains("x -424..599, y -243..780");
        });
    }

    @Test
    void fontKeysCarryNoTitleRowSoEveryProfileMustShareOne() {
        // GuiFonts names an offset font by screen row alone (<ns>:gui/default_y6). Two GUIs of one namespace would
        // clash on a key if their containers had different title rows, so the day a profile differs the key must
        // carry the row first (gui.md §12.5.3 as-built note)
        assertThat(GuiContainers.all()).extracting(GuiContainer::titleY).containsOnly(6);
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    /**
     * Replays a compiled title's pen from the container's origin: shifts add their offset, an art glyph advances by
     * its ink width plus one, a text run by its measured advance. Returns the pen after the last piece.
     */
    private static int penAtTheEndOf(String title, GuiCompilation compiled) {
        int pen = compiled.model().container().titleCentred() ? 8 : compiled.model().container().titleX();
        var shift = java.util.regex.Pattern.compile("<shift:(-?\\d+)>");
        var tile = java.util.regex.Pattern.compile("<font:[^>]*:gui><#FFFFFF>(.)</font>");
        var text = java.util.regex.Pattern.compile("<font:[^>]*/smallcaps_y\\d+><#[0-9A-F]{6}>([^<]*)</font>");
        int at = 0;
        var widths = tileWidths(compiled);
        while (at < title.length()) {
            var s = shift.matcher(title).region(at, title.length());
            var t = tile.matcher(title).region(at, title.length());
            var x = text.matcher(title).region(at, title.length());
            if (s.lookingAt()) {
                pen += Integer.parseInt(s.group(1));
                at = s.end();
            } else if (t.lookingAt()) {
                pen += widths.get((int) t.group(1).charAt(0)) + 1;
                at = t.end();
            } else if (x.lookingAt()) {
                pen += SmallcapsFont.measure(x.group(1));
                at = x.end();
            } else {
                throw new AssertionError("unexpected title text at " + at + ": " + title.substring(at));
            }
        }
        return pen;
    }

    /** The ink width of each art glyph, by codepoint, from the font providers' PNGs. */
    private static Map<Integer, Integer> tileWidths(GuiCompilation compiled) {
        var widths = new LinkedHashMap<Integer, Integer>();
        for (GuiCompilation.FontProvider font : compiled.fonts()) {
            if (font.provider().get("chars") instanceof List<?> chars && "bitmap".equals(font.provider().get("type"))
                    && font.fontKey().endsWith(":gui")) {
                String file = (String) font.provider().get("file");
                String asset = "assets/" + file.replaceFirst(":", "/textures/");
                var png = PngDecoder.decode(compiled.assets().get(asset)).orElseThrow();
                widths.put((int) ((String) chars.get(0)).charAt(0), png.width());
            }
        }
        return widths;
    }

    private void pin(GuiCompilation compiled, String golden) throws IOException {
        var out = new StringBuilder();
        out.append("container: ").append(compiled.model().container().id()).append('\n');
        out.append("title: ").append(compiled.model().title().replaceAll(PRIVATE_USE,
                "{E2xx}")).append('\n');
        compiled.fonts().forEach(font -> out.append("font ").append(font.fontKey()).append(' ')
                .append(font.provider().toString().replaceAll(PRIVATE_USE, "{E2xx}")
                        .replace("\u0000", "\\u0000")).append('\n'));
        compiled.assets().forEach((path, bytes) -> {
            var decoded = PngDecoder.decode(bytes).orElseThrow();
            out.append("asset ").append(path).append(' ').append(decoded.width()).append('x').append(decoded.height())
                    .append(' ').append(sha256(bytes)).append('\n');
        });
        compiled.model().slots().forEach(slot -> out.append("slot ").append(slot.slot()).append(' ')
                .append(slot.item()).append(" x").append(slot.amount()).append('\n'));
        for (int slot = 0; slot < compiled.model().container().slotCount(); slot++) {
            out.append("clicks ").append(slot).append(' ').append(compiled.model().clicks().layersAt(slot)).append('\n');
        }
        Path summary = temp.resolve("summary.txt");
        Files.writeString(summary, out.toString(), StandardCharsets.UTF_8);
        if (GoldenFiles.regenEnabled()) {
            GoldenFiles.writeGolden(summary, golden);
        }
        GoldenFiles.assertMatchesGolden(summary, golden);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
