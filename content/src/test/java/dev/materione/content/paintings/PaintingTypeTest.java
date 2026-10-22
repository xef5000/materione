package dev.materione.content.paintings;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.PackEmitter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Painting type behavior: defaults, texture resolution, datapack emission. */
class PaintingTypeTest {

    private final PaintingType type = new PaintingType();

    @Test
    void parsesWithDefaultsAndResolvesTexture() {
        var parsed = type.parse(
                NamespacedId.parse("sample:sunrise"),
                map("texture", "sunrise", "width", 2, "height", 1),
                Map.of());
        assertThat(parsed).isPresent();
        var painting = (PaintingDefinition) parsed.get();
        assertThat(painting.assetId()).isEqualTo("sample:sunrise");
        assertThat(painting.width()).isEqualTo(2);
        assertThat(painting.height()).isEqualTo(1);
        assertThat(painting.textureWidth()).isEqualTo(32);
        assertThat(painting.textureHeight()).isEqualTo(16);
        assertThat(painting.author()).isEqualTo("Unknown");
        assertThat(painting.title()).isEqualTo("sunrise");
    }

    @Test
    void explicitPaintingPrefixAndNamespaceAreKept() {
        var painting = parsePainting(map("texture", "other:canvas.png"));
        // the painting/ segment is an authoring location, not part of the
        // sprite id: vanilla's paintings atlas registers the texture as
        // other:canvas (directory source, empty prefix)
        assertThat(painting.assetId()).isEqualTo("other:canvas");
    }

    @Test
    void outOfRangeDimensionsAreRejected() {
        assertThat(type.parse(
                NamespacedId.parse("sample:x"), map("texture", "x", "width", 0), Map.of())).isEmpty();
        assertThat(type.parse(
                NamespacedId.parse("sample:x"), map("texture", "x", "height", 17), Map.of())).isEmpty();
        assertThat(type.parse(
                NamespacedId.parse("sample:x"), map("width", 2), Map.of())).isEmpty();
    }

    @Test
    void compileEmitsVariantAndPlaceableTag() {
        var painting = parsePainting(map("texture", "sunrise", "placeable", true, "title", "Sunrise"));
        var emitter = new RecordingEmitter();
        type.compile(painting, null, emitter, NO_ALLOCATIONS);

        assertThat(emitter.datapackFiles).hasSize(2);
        var variant = emitter.datapackFiles.get(
                "data/sample/painting_variant/sunrise.json");
        assertThat(variant)
                .containsEntry("asset_id", "sample:sunrise")
                .containsEntry("width", 1)
                .containsEntry("height", 1)
                .containsEntry("title", "Sunrise");
        var tag = emitter.datapackFiles.get("data/minecraft/tags/painting_variant/placeable.json");
        assertThat((List<?>) tag.get("values")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).containsExactly("sample:sunrise");
    }

    @Test
    void nonPlaceablePaintingsEmitNoTag() {
        var painting = parsePainting(map("texture", "sunrise"));
        var emitter = new RecordingEmitter();
        type.compile(painting, null, emitter, NO_ALLOCATIONS);
        assertThat(emitter.datapackFiles).containsOnlyKeys(
                "data/sample/painting_variant/sunrise.json");
    }

    @Test
    void selfDescriptionCoversTheAuthoringSurface() {
        var description = type.describe();
        assertThat(description.displayName()).isEqualTo("Painting");
        assertThat(description.parameters())
                .anySatisfy(p -> assertThat(p.id()).isEqualTo("texture"))
                .anySatisfy(p -> assertThat(p.id()).isEqualTo("placeable"));
    }

    // --- helpers -------------------------------------------------------------

    private PaintingDefinition parsePainting(Map<String, Object> definition) {
        return (PaintingDefinition) type.parse(
                NamespacedId.parse("sample:sunrise"), definition, Map.of()).orElseThrow();
    }

    private static final dev.materione.api.content.Allocations NO_ALLOCATIONS =
            new dev.materione.api.content.Allocations() {
                @Override
                public String allocate(NamespacedId resource, String kind) {
                    throw new IllegalStateException("paintings allocate nothing");
                }

                @Override
                public Optional<String> lookup(NamespacedId resource, String kind) {
                    return Optional.empty();
                }
            };

    private static final class RecordingEmitter implements PackEmitter {
        final Map<String, Map<String, Object>> datapackFiles = new LinkedHashMap<>();

        @Override
        public void datapackFile(String path, Map<String, Object> json) {
            datapackFiles.putIfAbsent(path, json);
        }

        @Override
        public void itemDefinition(NamespacedId id, Map<String, Object> json) {}

        @Override
        public void model(NamespacedId id, Map<String, Object> json) {}

        @Override
        public void copyAsset(Path sourceFile, String destination) {}

        @Override
        public void fontProvider(String fontKey, Map<String, Object> providerJson) {}

        @Override
        public void soundEvent(String soundKey, Map<String, Object> eventJson) {}

        @Override
        public void vanillaFile(String path, Map<String, Object> json) {
            throw new IllegalStateException("paintings must not emit vanilla-namespace files");
        }

        @Override
        public void description(String text) {}
    }

    private static Map<String, Object> map(Object... pairs) {
        var out = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

}
