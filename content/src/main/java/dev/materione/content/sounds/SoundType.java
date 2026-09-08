package dev.materione.content.sounds;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.content.Allocations;
import dev.materione.core.content.ContentType;
import dev.materione.core.content.PackEmitter;
import dev.materione.content.Templates;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The custom sound content type (M4, runtime-systems §10): parses
 * {@code sounds/*.yml} and compiles each definition into a sounds.json event.
 * Events group per namespace in the compiler; {@code replace_vanilla:} entries
 * go through the vanilla-override merge ({@code assets/minecraft/sounds.json})
 * instead. The .ogg files themselves are plain pack assets under
 * {@code assets/sounds/} — the client resolves whatever sounds.json points at.
 */
public final class SoundType implements ContentType {

    /**
     * Categories the client understands, in editor-dropdown order (master
     * first: it is the default). Validated lowercase, played server-side.
     */
    public static final List<String> CATEGORY_OPTIONS = List.of(
            "master", "music", "record", "weather", "block", "hostile",
            "neutral", "player", "ambient", "voice");

    public static final Set<String> CATEGORIES = Set.copyOf(CATEGORY_OPTIONS);

    private static final int MAX_SOURCES = 32;

    @Override
    public String name() {
        return "sound";
    }

    @Override
    public String directory() {
        return "sounds";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Sound",
                "A custom sound event (.ogg files under assets/sounds/) — playable by its namespaced id",
                List.of(
                        new ParameterSchema("sounds", ParameterType.LIST, true, null,
                                "Source files under assets/sounds/ (one or more, randomized)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Sources", "audio", 10, false, "list", "", "ui/level_up")),
                        new ParameterSchema("subtitle", ParameterType.STRING, false, null,
                                "Subtitle shown when subtitles are on", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Subtitle", "audio", 20, false, "text", "", "Level up!")),
                        new ParameterSchema("volume", ParameterType.DECIMAL, false, "1.0",
                                "Base volume", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Volume", "audio", 30, false, "number", "", "1.0")),
                        new ParameterSchema("pitch", ParameterType.DECIMAL, false, "1.0",
                                "Base pitch", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Pitch", "audio", 40, false, "number", "", "1.0")),
                        new ParameterSchema("category", ParameterType.STRING, false, "master",
                                "Sound category the client groups under its music & sounds options", CATEGORY_OPTIONS, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Category", "audio", 50, true, "select", "", "master")),
                        new ParameterSchema("stream", ParameterType.BOOLEAN, false, "false",
                                "Stream from disk instead of loading into memory (long files)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Stream", "audio", 60, true, "boolean", "", "false")),
                        new ParameterSchema("weight", ParameterType.INTEGER, false, "1",
                                "Pick weight when several sources are set", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Weight", "audio", 70, true, "integer", "", "1")),
                        new ParameterSchema("preload", ParameterType.BOOLEAN, false, "false",
                                "Load with the pack instead of on first play", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Preload", "audio", 80, true, "boolean", "", "false")),
                        new ParameterSchema("attenuation_distance", ParameterType.INTEGER, false, null,
                                "Distance the sound carries (vanilla default 16)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Attenuation", "audio", 90, true, "integer", "", "16")),
                        new ParameterSchema("replace", ParameterType.BOOLEAN, false, "false",
                                "Replace same-named entries from lower packs instead of adding", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Replace", "audio", 100, true, "boolean", "", "false")),
                        new ParameterSchema("replace_vanilla", ParameterType.STRING, false, null,
                                "Vanilla sound event this definition overrides (e.g. block.wood.step)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Override vanilla", "audio", 110, true, "text", "", "")),
                        new ParameterSchema("jukebox", ParameterType.RAW, false, null,
                                "Music-disc song section: description, duration, comparator_output, range", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Jukebox song", "audio", 120, true, "advanced", "", ""))),
                "1.0.0");
    }

    @Override
    public Optional<Object> parse(
            NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
        var merged = Templates.merge(definition, id.namespace(), templates);
        if (merged == null) {
            return Optional.empty();
        }
        List<String> sources = resolveSources(id.namespace(), stringList(merged.get("sounds")));
        if (sources.isEmpty() || sources.size() > MAX_SOURCES) {
            return Optional.empty();
        }
        String category = textOrDefault(merged, "category", "master").toLowerCase(java.util.Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            return Optional.empty();
        }
        String replaceVanilla = Templates.text(merged, "replace_vanilla");
        Object jukebox = merged.get("jukebox");
        if (jukebox != null && !(jukebox instanceof Map<?, ?>)) {
            return Optional.empty();
        }
        return Optional.of(new SoundDefinition(
                id,
                sources,
                category,
                floatOf(merged, "volume", 1.0f),
                floatOf(merged, "pitch", 1.0f),
                intOf(merged, "weight", 1),
                boolOf(merged, "stream", false),
                boolOf(merged, "preload", false),
                merged.get("attenuation_distance") instanceof Number distance ? distance.intValue() : null,
                Templates.text(merged, "subtitle"),
                boolOf(merged, "replace", false),
                replaceVanilla,
                jukebox == null ? null : stringKeyedMap((Map<?, ?>) jukebox)));
    }

    /** A string-keyed writable copy of a nested authored map. */
    private static Map<String, Object> stringKeyedMap(Map<?, ?> source) {
        var out = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }

    @Override
    public void compile(
            Object definition, NamespacedId allocatedModelId, PackEmitter emitter, Allocations allocations) {
        if (!(definition instanceof SoundDefinition sound)) {
            return;
        }
        var event = new LinkedHashMap<String, Object>();
        event.put("sounds", sound.sources());
        if (sound.subtitle() != null) {
            event.put("subtitle", sound.subtitle());
        }
        if (sound.volume() != 1.0f) {
            event.put("volume", sound.volume());
        }
        if (sound.pitch() != 1.0f) {
            event.put("pitch", sound.pitch());
        }
        if (sound.weight() != 1) {
            event.put("weight", sound.weight());
        }
        if (sound.stream()) {
            event.put("stream", true);
        }
        if (sound.preload()) {
            event.put("preload", true);
        }
        if (sound.attenuationDistance() != null && sound.attenuationDistance() != 16) {
            event.put("attenuation_distance", sound.attenuationDistance());
        }
        if (sound.replace()) {
            event.put("replace", true);
        }
        if (sound.replaceVanilla() != null) {
            // vanilla override: the key is the vanilla event's own name
            emitter.vanillaFile("sounds.json", Map.of(sound.replaceVanilla(), event));
        } else {
            emitter.soundEvent(sound.id().toString(), event);
        }
        if (sound.isSong()) {
            emitter.datapackFile(
                    "data/" + sound.id().namespace() + "/jukebox_song/" + sound.id().value() + ".json",
                    jukeboxSongJson(sound));
        }
    }

    /**
     * The 1.21.2+ {@code jukebox_song} registry entry (Nexo's exact shape).
     * Restart-required: the song exists on the server once the generated
     * datapack loads at the next boot.
     */
    static Map<String, Object> jukeboxSongJson(SoundDefinition sound) {
        Map<String, Object> jukebox = sound.jukebox();
        var out = new LinkedHashMap<String, Object>();
        var soundEvent = new LinkedHashMap<String, Object>();
        soundEvent.put("sound_id", sound.id().toString());
        Object range = jukebox.get("range");
        if (range instanceof Number number) {
            soundEvent.put("range", number.floatValue());
        }
        out.put("sound_event", soundEvent);
        Object description = jukebox.get("description");
        out.put("description", description instanceof String text && !text.isBlank()
                ? text : sound.id().value());
        float duration = jukebox.get("duration") instanceof Number number
                ? number.floatValue() : 120.0f;
        out.put("length_in_seconds", Math.max(1.0f, duration));
        int comparator = jukebox.get("comparator_output") instanceof Number number
                ? number.intValue() : 15;
        out.put("comparator_output", Math.max(0, Math.min(15, comparator)));
        return out;
    }

    // --- helpers -------------------------------------------------------------

    /** {@code ui/level_up[.ogg]} → {@code <ns>:ui/level_up}; explicit namespaces win. */
    static List<String> resolveSources(String namespace, List<String> raw) {
        var out = new ArrayList<String>(raw.size());
        for (String source : raw) {
            String cleaned = source.replace('\\', '/');
            if (cleaned.toLowerCase(java.util.Locale.ROOT).endsWith(".ogg")) {
                cleaned = cleaned.substring(0, cleaned.length() - 4);
            }
            out.add(cleaned.contains(":") ? cleaned : namespace + ":" + cleaned);
        }
        return List.copyOf(out);
    }

    private static float floatOf(Map<String, Object> map, String key, float fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.floatValue() : fallback;
    }

    private static int intOf(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean boolOf(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static String textOrDefault(Map<String, Object> map, String key, String fallback) {
        String value = Templates.text(map, key);
        return value != null ? value : fallback;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (Object entry : list) {
            if (entry instanceof String s && !s.isBlank()) {
                out.add(s);
            } else if (entry != null) {
                return List.of(); // a non-string source is a broken definition
            }
        }
        return out;
    }
}
