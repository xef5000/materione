package dev.materione.content.gui.raster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.materione.api.NamespacedId;
import dev.materione.api.gui.TextureSource;
import dev.materione.content.gui.GuiCompiler;
import dev.materione.content.gui.GuiDefinition;
import dev.materione.content.gui.GuiTestSupport;
import dev.materione.content.gui.GuiType;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.texture.PngDecoder;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The shared raster vectors (gui.md §4, {@code fixtures/gui/raster/README.md}): every
 * vector's layers go through the GUI type's own parsers, are flattened by
 * {@link GuiCompiler#flatten}, and must reproduce the expected PNG pixel for pixel. The
 * comparison is on decoded ARGB values; alpha 0 is compared as {@code 0x00000000} on both
 * sides, which is the vectors' storage rule.
 *
 * <p>The vectors are authored by the studio (#186) and never regenerated in CI. The test is
 * skipped, with a message, when the directory is absent.
 */
class RasterVectorsTest {

    private static final Path DIRECTORY = GoldenFiles.fixturesRoot().resolve("gui").resolve("raster");

    @TestFactory
    Stream<DynamicTest> everyVectorMatchesItsExpectedPixels() throws IOException {
        assumeTrue(Files.isDirectory(DIRECTORY),
                "fixtures/gui/raster is absent: the shared raster vectors come from issue #186");
        List<Path> vectors;
        try (var files = Files.list(DIRECTORY)) {
            vectors = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(vectors).as("the vector set").isNotEmpty();
        return vectors.stream().map(json -> DynamicTest.dynamicTest(
                json.getFileName().toString(), () -> verify(json)));
    }

    private static void verify(Path json) throws IOException {
        String name = json.getFileName().toString().replace(".json", "");
        Map<String, Object> vector = LosslessYaml.parse(Files.readString(json)).data();
        @SuppressWarnings("unchecked")
        Map<String, Object> canvas = (Map<String, Object>) vector.get("canvas");
        int width = ((Number) canvas.get("width")).intValue();
        int height = ((Number) canvas.get("height")).intValue();
        int rows = vector.get("rows") instanceof Number count ? count.intValue() : 1;
        Map<String, TextureSource.Pixels> images = images(vector);

        // the layers as a GUI file would carry them, through the real parsers
        var definition = new LinkedHashMap<String, Object>();
        definition.put("title", name);
        definition.put("rows", rows);
        definition.put("layers", vector.get("layers"));
        GuiType type = GuiTestSupport.type();
        var parsed = type.parse(NamespacedId.of(GuiTestSupport.NAMESPACE, "vector"), definition, Map.of());
        assertThat(parsed).as(name + ": the vector's GUI parses").isPresent();
        GuiDefinition gui = (GuiDefinition) parsed.get();

        TextureSource textures = reference -> Optional.ofNullable(images.get(reference));
        var skipped = new ArrayList<String>();
        var actual = GuiCompiler.flatten(width, height, gui.layers(), textures,
                (layer, reason) -> skipped.add(layer.id() + ": " + reason));

        var expected = PngDecoder.decode(Files.readAllBytes(DIRECTORY.resolve(name + ".png")))
                .orElseThrow(() -> new AssertionError(name + ": expected PNG is unreadable"));
        assertThat(expected.width()).as(name + ": expected width").isEqualTo(width);
        assertThat(expected.height()).as(name + ": expected height").isEqualTo(height);

        var mismatches = new ArrayList<String>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int want = normalise(expected.argb()[y * width + x]);
                int got = normalise(actual.argb(x, y));
                if (want != got && mismatches.size() < 10) {
                    mismatches.add(String.format("(%d,%d) expected %08X got %08X", x, y, want, got));
                }
            }
        }
        assertThat(mismatches).as(name + ": pixels differ (skipped layers: " + skipped + ")").isEmpty();
    }

    /** The vector's inline bitmaps, by reference, decoded from its {@code #AARRGGBB} / {@code #RRGGBB} strings. */
    private static Map<String, TextureSource.Pixels> images(Map<String, Object> vector) {
        var images = new LinkedHashMap<String, TextureSource.Pixels>();
        if (!(vector.get("images") instanceof Map<?, ?> declared)) {
            return images;
        }
        for (var entry : declared.entrySet()) {
            @SuppressWarnings("unchecked")
            var image = (Map<String, Object>) entry.getValue();
            int width = ((Number) image.get("width")).intValue();
            int height = ((Number) image.get("height")).intValue();
            List<?> pixels = (List<?>) image.get("pixels");
            int[] argb = new int[pixels.size()];
            for (int index = 0; index < argb.length; index++) {
                argb[index] = colour((String) pixels.get(index));
            }
            images.put((String) entry.getKey(), new TextureSource.Pixels(width, height, argb));
        }
        return images;
    }

    /** {@code #RRGGBB} (opaque) or {@code #AARRGGBB} (alpha first), as straight ARGB with alpha 0 stored as 0. */
    static int colour(String text) {
        if (text.matches("#[0-9A-Fa-f]{6}")) {
            return 0xFF000000 | Integer.parseUnsignedInt(text.substring(1), 16);
        }
        if (text.matches("#[0-9A-Fa-f]{8}")) {
            int value = Integer.parseUnsignedInt(text.substring(1), 16);
            return (value >>> 24) == 0 ? 0 : value;
        }
        throw new AssertionError("not a colour: " + text);
    }

    private static int normalise(int argb) {
        return (argb >>> 24) == 0 ? 0 : argb;
    }
}
