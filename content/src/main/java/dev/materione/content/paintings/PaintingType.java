package dev.materione.content.paintings;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.content.Templates;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The custom painting content type (M4, runtime-systems §10): parses
 * {@code paintings/*.yml} and compiles each definition into a
 * {@code painting_variant} datapack entry (legacy servers) — on modern
 * servers the variant is registered through Paper's registry events
 * instead. Placement is vanilla — placeable variants join the
 * {@code minecraft:placeable} painting-variant tag.
 *
 * <p>The texture lives at {@code assets/<ns>/textures/painting/<path>.png}
 * in the resource pack; the asset_id is {@code <ns>:<path>} — vanilla's
 * paintings atlas sweeps that directory across all namespaces with an empty
 * prefix, so the sprite id (and therefore the asset_id) must not carry the
 * {@code painting/} segment.
 */
public final class PaintingType implements ContentType {

    private static final int MAX_BLOCKS = 16;

    @Override
    public String name() {
        return "painting";
    }

    @Override
    public String directory() {
        return "paintings";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Painting",
                "A custom painting variant — texture, block size, author and title",
                List.of(
                        new ParameterSchema("texture", ParameterType.REFERENCE, true, null,
                                "Texture under assets/textures/painting/ (exactly width×16 by height×16 px)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Texture", "appearance", 10, false, "texture", "texture", "painting/sunrise")),
                        new ParameterSchema("width", ParameterType.INTEGER, false, "1",
                                "Width in blocks (1..16)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Width (blocks)", "appearance", 20, false, "integer", "", "1")),
                        new ParameterSchema("height", ParameterType.INTEGER, false, "1",
                                "Height in blocks (1..16)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Height (blocks)", "appearance", 30, false, "integer", "", "1")),
                        new ParameterSchema("author", ParameterType.STRING, false, "Unknown",
                                "Shown in the variant tooltip", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Author", "identity", 10, false, "formatted-text", "", "Unknown")),
                        new ParameterSchema("title", ParameterType.STRING, false, null,
                                "Shown in the variant tooltip (defaults to the id)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Title", "identity", 20, false, "formatted-text", "", "Sunrise")),
                        new ParameterSchema("placeable", ParameterType.BOOLEAN, false, "false",
                                "Join the vanilla random-placement pool", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Placeable", "behavior", 10, false, "boolean", "", "false"))),
                "1.0.0");
    }

    @Override
    public Optional<Object> parse(
            NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
        var merged = Templates.merge(definition, id.namespace(), templates);
        if (merged == null) {
            return Optional.empty();
        }
        String texture = Templates.text(merged, "texture");
        if (texture == null) {
            return Optional.empty();
        }
        int width = intOf(merged, "width", 1);
        int height = intOf(merged, "height", 1);
        if (width < 1 || width > MAX_BLOCKS || height < 1 || height > MAX_BLOCKS) {
            return Optional.empty();
        }
        String title = Templates.text(merged, "title");
        return Optional.of(new PaintingDefinition(
                id,
                resolveTexture(id.namespace(), texture),
                width,
                height,
                textOrDefault(merged, "author", "Unknown"),
                title == null ? id.value() : title,
                boolOf(merged, "placeable", false)));
    }

    @Override
    public void compile(
            Object definition, NamespacedId allocatedModelId, PackEmitter emitter, Allocations allocations) {
        if (!(definition instanceof PaintingDefinition painting)) {
            return;
        }
        var variant = new LinkedHashMap<String, Object>();
        variant.put("asset_id", painting.assetId());
        variant.put("width", painting.width());
        variant.put("height", painting.height());
        // rich text (master §5.7): plain strings stay strings, formatting becomes a component
        variant.put("author", emitter.textComponent(painting.author()));
        variant.put("title", emitter.textComponent(painting.title()));
        emitter.datapackFile(
                "data/" + painting.id().namespace() + "/painting_variant/" + painting.id().value() + ".json",
                variant);
        if (painting.placeable()) {
            // the vanilla tag gates survival placement; tags merge additively
            emitter.datapackFile(
                    "data/minecraft/tags/painting_variant/placeable.json",
                    Map.of("replace", false, "values", List.of(painting.id().toString())));
        }
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Texture refs resolve under textures/painting/ (authoring convention):
     * bare names gain the prefix and explicit namespaces win — but the
     * resulting asset_id drops the prefix, because the client's paintings
     * atlas registers the texture as sprite {@code <ns>:<path>} (vanilla
     * directory source, empty prefix). Keeping the prefix here would point
     * the variant at a sprite that never exists (checkerboard painting).
     */
    private static String resolveTexture(String namespace, String texture) {
        String cleaned = texture.replace('\\', '/');
        if (cleaned.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        int colon = cleaned.indexOf(':');
        String path = colon >= 0 ? cleaned.substring(colon + 1) : cleaned;
        if (path.startsWith("painting/")) {
            path = path.substring("painting/".length());
        }
        return colon >= 0 ? cleaned.substring(0, colon + 1) + path : namespace + ":" + path;
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
}
