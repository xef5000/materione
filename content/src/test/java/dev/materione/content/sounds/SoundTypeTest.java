package dev.materione.content.sounds;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.core.content.PackEmitter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Sound type behavior: source resolution, event json, vanilla override routing. */
class SoundTypeTest {

    private final SoundType type = new SoundType();

    @Test
    void parsesSourcesWithDefaults() {
        var parsed = type.parse(
                NamespacedId.parse("sample:level_up"),
                map("sounds", List.of("ui/level_up.ogg")),
                Map.of());
        assertThat(parsed).isPresent();
        var sound = (SoundDefinition) parsed.get();
        assertThat(sound.sources()).containsExactly("sample:ui/level_up");
        assertThat(sound.category()).isEqualTo("master");
        assertThat(sound.volume()).isEqualTo(1.0f);
        assertThat(sound.isSong()).isFalse();
    }

    @Test
    void sourceExtensionsAndNamespacesNormalize() {
        List<String> sources = SoundType.resolveSources("sample", List.of("a.ogg", "b\\c.OGG", "other:d"));
        assertThat(sources).containsExactly("sample:a", "sample:b/c", "other:d");
    }

    @Test
    void invalidCategoryOrMissingSourcesAreRejected() {
        assertThat(type.parse(
                NamespacedId.parse("sample:x"), map("sounds", List.of("a.ogg"), "category", "warp"),
                Map.of())).isEmpty();
        assertThat(type.parse(
                NamespacedId.parse("sample:x"), map("subtitle", "no sources"), Map.of())).isEmpty();
        assertThat(type.parse(
                NamespacedId.parse("sample:x"),
                map("sounds", List.of("a.ogg"), "jukebox", "not-a-map"), Map.of())).isEmpty();
    }

    @Test
    void compileEmitsMinimalEventJson() {
        var sound = parseSound(map("sounds", List.of("ui/level_up.ogg"), "subtitle", "Level up!"));
        var emitter = new RecordingEmitter();
        type.compile(sound, null, emitter, NO_ALLOCATIONS);

        assertThat(emitter.soundEvents).hasSize(1);
        var emission = emitter.soundEvents.get(0);
        assertThat(emission.key()).isEqualTo("sample:sound");
        assertThat(emission.event())
                .containsEntry("sounds", List.of("sample:ui/level_up"))
                .containsEntry("subtitle", "Level up!");
        // defaults stay out of the json
        assertThat(emission.event()).doesNotContainKey("volume");
        assertThat(emission.event()).doesNotContainKey("stream");
    }

    @Test
    void nonDefaultsLandInTheEventJson() {
        var sound = parseSound(map(
                "sounds", List.of("a.ogg", "b.ogg"),
                "volume", 0.5, "pitch", 1.5, "weight", 2,
                "stream", true, "preload", true, "attenuation_distance", 8, "replace", true));
        var emitter = new RecordingEmitter();
        type.compile(sound, null, emitter, NO_ALLOCATIONS);
        var event = emitter.soundEvents.get(0).event();
        assertThat(event)
                .containsEntry("volume", 0.5f)
                .containsEntry("pitch", 1.5f)
                .containsEntry("weight", 2)
                .containsEntry("stream", true)
                .containsEntry("preload", true)
                .containsEntry("attenuation_distance", 8)
                .containsEntry("replace", true);
        assertThat((List<?>) event.get("sounds")).hasSize(2);
    }

    @Test
    void replaceVanillaRoutesThroughTheOverrideSeam() {
        var sound = parseSound(map(
                "sounds", List.of("wood_step"),
                "replace_vanilla", "block.wood.step", "replace", true));
        var emitter = new RecordingEmitter();
        type.compile(sound, null, emitter, NO_ALLOCATIONS);

        assertThat(emitter.soundEvents).isEmpty();
        assertThat(emitter.vanillaFiles).hasSize(1);
        var override = emitter.vanillaFiles.get(0);
        assertThat(override.path()).isEqualTo("sounds.json");
        assertThat(override.json()).containsKey("block.wood.step");
    }

    @Test
    void jukeboxSectionIsCarriedForTheDatapackStage() {
        var sound = parseSound(map(
                "sounds", List.of("anthem.ogg"),
                "jukebox", map("description", "Anthem", "duration", 90, "comparator_output", 7, "range", 1.0)));
        assertThat(sound.isSong()).isTrue();
        assertThat(sound.jukebox()).containsEntry("comparator_output", 7);
    }

    @Test
    void selfDescriptionCoversTheAuthoringSurface() {
        var description = type.describe();
        assertThat(description.displayName()).isEqualTo("Sound");
        assertThat(description.parameters())
                .anySatisfy(p -> assertThat(p.id()).isEqualTo("sounds"))
                .anySatisfy(p -> assertThat(p.id()).isEqualTo("jukebox"));
    }

    @Test
    void categoryIsADropdownOfTheAcceptedValues() {
        var category = type.describe().parameters().stream()
                .filter(p -> p.id().equals("category"))
                .findFirst().orElseThrow();
        assertThat(category.allowedValues()).containsExactlyElementsOf(SoundType.CATEGORY_OPTIONS);
        assertThat(category.editor().widget()).isEqualTo("select");
        assertThat(SoundType.CATEGORY_OPTIONS).contains(category.editor().example());
    }

    // --- helpers -------------------------------------------------------------

    private SoundDefinition parseSound(Map<String, Object> definition) {
        return (SoundDefinition) type.parse(
                NamespacedId.parse("sample:sound"), definition, Map.of()).orElseThrow();
    }

    private record SoundEmitted(String key, Map<String, Object> event) {}

    private record VanillaEmitted(String path, Map<String, Object> json) {}

    private static final dev.materione.core.content.Allocations NO_ALLOCATIONS =
            new dev.materione.core.content.Allocations() {
                @Override
                public String allocate(NamespacedId resource, String kind) {
                    throw new IllegalStateException("sounds allocate nothing");
                }

                @Override
                public Optional<String> lookup(NamespacedId resource, String kind) {
                    return Optional.empty();
                }
            };

    private static final class RecordingEmitter implements PackEmitter {
        final List<SoundEmitted> soundEvents = new ArrayList<>();
        final List<VanillaEmitted> vanillaFiles = new ArrayList<>();

        @Override
        public void soundEvent(String soundKey, Map<String, Object> eventJson) {
            soundEvents.add(new SoundEmitted(soundKey, eventJson));
        }

        @Override
        public void vanillaFile(String path, Map<String, Object> json) {
            vanillaFiles.add(new VanillaEmitted(path, json));
        }

        @Override
        public void itemDefinition(NamespacedId id, Map<String, Object> json) {}

        @Override
        public void model(NamespacedId id, Map<String, Object> json) {}

        @Override
        public void copyAsset(Path sourceFile, String destination) {}

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
