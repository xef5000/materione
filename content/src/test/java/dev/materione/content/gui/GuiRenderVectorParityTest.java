package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.authoring.AuthoringApi.ModelAsset;
import dev.materione.api.gui.GuiAuthoringApi;
import dev.materione.api.gui.GuiAuthoringApi.GuiDocumentView;
import dev.materione.api.gui.GuiAuthoringApi.GuiRender;
import dev.materione.api.gui.GuiAuthoringApi.GuiRenderRequest;
import dev.materione.api.gui.GuiAuthoringApi.Layer;
import dev.materione.api.gui.GuiAuthoringApi.Typed;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.registry.Registry;
import dev.materione.content.gui.raster.PngEncoder;
import dev.materione.core.authoring.AuthoringApiImpl;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.document.ModelTransactionService;
import dev.materione.core.texture.PngDecoder;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Render-of-a-draft parity with the shared raster vectors (gui.md §4, the vectors' README): each
 * vector whose canvas is a chest's own canvas is rendered through {@link GuiAuthoringApi#render} as
 * an unsaved typed draft, its inline bitmaps as draft PNGs, and the background must match the
 * vector's expected PNG pixel for pixel. The other vectors have canvases no GUI has (a 32 × 24 paint
 * test, say), so the API cannot draw them; {@code RasterVectorsTest} pins those through the same
 * flatten function the render calls.
 */
class GuiRenderVectorParityTest {

    private static final Path DIRECTORY = GoldenFiles.fixturesRoot().resolve("gui").resolve("raster");
    private static final String PACK = "vector";

    @TempDir
    Path temp;

    @TestFactory
    Stream<DynamicTest> chestVectorsRenderTheirExpectedPixels() throws IOException {
        List<Path> vectors;
        try (var files = Files.list(DIRECTORY)) {
            vectors = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        var chests = new ArrayList<DynamicTest>();
        for (Path json : vectors) {
            Map<String, Object> vector = LosslessYaml.parse(Files.readString(json)).data();
            @SuppressWarnings("unchecked")
            var canvas = (Map<String, Object>) vector.get("canvas");
            int width = ((Number) canvas.get("width")).intValue();
            int height = ((Number) canvas.get("height")).intValue();
            int rows = vector.get("rows") instanceof Number count ? count.intValue() : 1;
            if (width == CompiledGui.WIDTH && height == CompiledGui.height(rows)) {
                chests.add(DynamicTest.dynamicTest(json.getFileName().toString(), () -> verify(json)));
            }
        }
        assertThat(chests).as("the chest-sized vectors").hasSizeGreaterThanOrEqualTo(6);
        return chests.stream();
    }

    private void verify(Path json) throws IOException {
        String name = json.getFileName().toString().replace(".json", "");
        Map<String, Object> vector = LosslessYaml.parse(Files.readString(json)).data();
        int rows = vector.get("rows") instanceof Number count ? count.intValue() : 1;

        var assets = new ArrayList<ModelAsset>();
        if (vector.get("images") instanceof Map<?, ?> images) {
            for (var entry : images.entrySet()) {
                @SuppressWarnings("unchecked")
                var image = (Map<String, Object>) entry.getValue();
                int width = ((Number) image.get("width")).intValue();
                int height = ((Number) image.get("height")).intValue();
                List<?> pixels = (List<?>) image.get("pixels");
                int[] argb = new int[pixels.size()];
                for (int index = 0; index < argb.length; index++) {
                    argb[index] = colour((String) pixels.get(index));
                }
                assets.add(new ModelAsset("assets/textures/" + entry.getKey() + ".png",
                        PngEncoder.encodeRgba(width, height, argb)));
            }
        }

        var layers = new ArrayList<Layer>();
        for (Object entry : (List<?>) vector.get("layers")) {
            layers.add(layer(entry));
        }
        var draft = new Typed(new GuiDocumentView(PACK, null, "vector", name, rows, 0, layers));

        GuiAuthoringApi gui = api();
        ApiResult<GuiRender> rendered = gui.render(new GuiRenderRequest(draft, assets));
        assertThat(rendered.ok()).as(name + ": " + rendered.diagnostics()).isTrue();
        GuiRender render = rendered.value();
        assertThat(render.diagnostics()).as(name + ": no layer skipped").isEmpty();

        var actual = PngDecoder.decode(render.background()).orElseThrow();
        var expected = PngDecoder.decode(Files.readAllBytes(DIRECTORY.resolve(name + ".png")))
                .orElseThrow(() -> new AssertionError(name + ": expected PNG is unreadable"));
        assertThat(actual.width()).as(name + ": width").isEqualTo(expected.width());
        assertThat(actual.height()).as(name + ": height").isEqualTo(expected.height());
        var mismatches = new ArrayList<String>();
        for (int index = 0; index < expected.argb().length && mismatches.size() < 10; index++) {
            int want = normalise(expected.argb()[index]);
            int got = normalise(actual.argb()[index]);
            if (want != got) {
                mismatches.add(String.format("(%d,%d) expected %08X got %08X",
                        index % expected.width(), index / expected.width(), want, got));
            }
        }
        assertThat(mismatches).as(name + ": pixels differ").isEmpty();
    }

    /** A vector layer as the typed view carries it: the gui type's common fields, the component's own. */
    private static Layer layer(Object entry) {
        @SuppressWarnings("unchecked")
        var fields = (Map<String, Object>) entry;
        var parameters = new LinkedHashMap<String, Object>();
        fields.forEach((key, value) -> {
            if (!List.of("id", "type", "name", "visible", "locked", "opacity").contains(key)) {
                parameters.put(key, value);
            }
        });
        return new Layer(
                (String) fields.get("id"),
                (String) fields.get("type"),
                (String) fields.get("name"),
                !Boolean.FALSE.equals(fields.get("visible")),
                Boolean.TRUE.equals(fields.get("locked")),
                fields.get("opacity") instanceof Number opacity ? opacity.intValue() : null,
                parameters);
    }

    private GuiAuthoringApi api() throws IOException {
        Path packsRoot = temp.resolve("packs");
        Files.createDirectories(packsRoot.resolve(PACK));
        DocumentStore store = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        Registry<GuiComponent> components = GuiTestSupport.components();
        AuthoringApiImpl authoring = new AuthoringApiImpl(store, audit, packsRoot,
                new ModelTransactionService(store, packsRoot, audit), null, () -> null, (actor, pack, changed) -> { },
                null, List::of,
                base -> new GuiAuthoringApiImpl(base, components, packsRoot, MemoryAllocations::new, List.of()));
        return authoring.gui();
    }

    /** {@code #RRGGBB} (opaque) or {@code #AARRGGBB}, straight ARGB, alpha 0 stored as 0. */
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
