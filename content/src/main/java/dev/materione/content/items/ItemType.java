package dev.materione.content.items;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.content.ResourcePresentation;
import dev.materione.content.Templates;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The item content type (M1): parses item YAML (templates resolved, diamond
 * inheritance rejected), self-describes its schema for the registry (the one
 * schema source, D5/§5.6), and compiles definitions to 1.21.4+ item-model
 * output through the {@link PackEmitter}.
 *
 * <p>Generated models: {@code texture:} (with optional {@code model_parent:})
 * produce {@code assets/<ns>/models/<id>.json} plus an item definition
 * referencing it — the auto-model path. {@code model:} references a
 * hand-authored model verbatim. The ItemModel tree builder (range_dispatch,
 * select, conditions) grows from this surface in M2+.
 */
public final class ItemType implements ContentType {

    private final List<String> supportedMaterials;

    /** No material palette is assumed by the pure-JVM content module. */
    public ItemType() {
        this(List.of());
    }

    /** Uses the platform's authoritative item-material palette when available. */
    public ItemType(List<String> supportedMaterials) {
        this.supportedMaterials = supportedMaterials == null ? List.of()
                : supportedMaterials.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .distinct()
                        .sorted(Comparator.naturalOrder())
                        .toList();
    }

    @Override
    public String name() {
        return "item";
    }

    @Override
    public String directory() {
        return "items";
    }

    @Override
    public SelfDescription describe() {
        ParameterType materialType = supportedMaterials.isEmpty()
                ? ParameterType.STRING : ParameterType.ENUM;
        return new SelfDescription(
                "Item",
                "A custom item: identity, appearance (item model), data components, behavior",
                List.of(
                        new ParameterSchema("material", materialType, true, null,
                                "Vanilla base material (e.g. NETHERITE_SWORD)", supportedMaterials, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Base material", "identity", 10, false, "material", "", "NETHERITE_SWORD")),
                        new ParameterSchema("template", ParameterType.REFERENCE, false, null,
                                "Template this item inherits from", null, null).withEditor(new ParameterSchema.EditorMetadata("Template", "identity", 20, true, "reference", "template", "")),
                        new ParameterSchema("model", ParameterType.REFERENCE, false, null,
                                "Hand-authored model reference (assets/<ns>/models/<id>.json)", null, null).withEditor(new ParameterSchema.EditorMetadata("3D model", "appearance", 20, false, "model", "model", "")),
                        new ParameterSchema("texture", ParameterType.REFERENCE, false, null,
                                "Texture for the generated model (auto-model path)", null, null).withEditor(new ParameterSchema.EditorMetadata("Texture", "appearance", 10, false, "texture", "texture", "item/my_texture")),
                        new ParameterSchema("model_parent", ParameterType.STRING, false, "item/generated",
                                "Parent for the generated model", null, null).withEditor(new ParameterSchema.EditorMetadata("Model style", "appearance", 30, true, "text", "", "item/generated")),
                        new ParameterSchema("display_name", ParameterType.STRING, false, null,
                                "Item display name", null, null).withEditor(new ParameterSchema.EditorMetadata("Display name", "identity", 0, false, "formatted-text", "", "Ruby sword")),
                        new ParameterSchema("lore", ParameterType.LIST, false, null,
                                "Lore lines", null, null).withEditor(new ParameterSchema.EditorMetadata("Lore", "gameplay", 10, false, "formatted-lines", "", "A very special item")),
                        new ParameterSchema("components", ParameterType.RAW, false, null,
                                "Vanilla data components (version-aware, platform-gated)", null, null).withEditor(new ParameterSchema.EditorMetadata("Vanilla components", "gameplay", 90, true, "advanced", "", "")),
                        new ParameterSchema("mechanics", ParameterType.LIST, false, null,
                                "Mechanic bundle: one map per mechanic ({name: parameters})", null, null).withEditor(new ParameterSchema.EditorMetadata("Mechanics", "behavior", 10, false, "structured-list", "mechanic", "")),
                        new ParameterSchema("jukebox_song", ParameterType.REFERENCE, false, null,
                                "Sound (with a jukebox section) this disc plays in jukeboxes", null, null).withEditor(new ParameterSchema.EditorMetadata("Jukebox song", "gameplay", 20, true, "reference", "sound", ""))),
                "1.0.0");
    }

    @Override
    public Optional<ResourcePresentation> presentation(Object parsedDefinition) {
        if (!(parsedDefinition instanceof ItemDefinition item)) {
            return Optional.empty();
        }
        return Optional.of(new ResourcePresentation(
                item.displayName(), item.material(), item.texture(), item.model()));
    }

    @Override
    public Optional<Object> parse(
            NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
        var merged = Templates.merge(definition, id.namespace(), templates);
        if (merged == null) {
            return Optional.empty(); // unknown or diamond template — quarantine upstream
        }
        Object material = merged.get("material");
        if (!(material instanceof String materialText) || materialText.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ItemDefinition(
                id,
                materialText,
                Templates.text(definition, "template"),
                Templates.text(merged, "model"),
                Templates.text(merged, "texture"),
                textOrDefault(merged, "model_parent", "item/generated"),
                Templates.text(merged, "display_name"),
                stringList(merged.get("lore")),
                nestedMap(merged.get("components")),
                behaviorMaps(merged.get("behavior")),
                behaviorMaps(merged.get("mechanics")),
                jukeboxSong(merged, id),
                merged));
    }

    /** {@code jukebox_song:} full or pack-relative sound reference. */
    private static String jukeboxSong(Map<String, Object> merged, NamespacedId id) {
        String raw = Templates.text(merged, "jukebox_song");
        return raw == null ? null : Templates.shortRef(id.namespace(), raw);
    }

    @Override
    public void compile(
            Object definition, NamespacedId allocatedModelId, PackEmitter emitter, Allocations allocations) {
        if (!(definition instanceof ItemDefinition item)) {
            return;
        }
        String modelRef = item.model();
        if (item.wantsGeneratedModel()) {
            // auto-model: generate the model, then reference it from the item definition
            modelRef = allocatedModelId.toString();
            var textures = new LinkedHashMap<String, Object>();
            textures.put("layer0", item.texture().contains(":")
                    ? item.texture()
                    : item.id().namespace() + ":" + item.texture());
            var model = new LinkedHashMap<String, Object>();
            model.put("parent", item.modelParent());
            model.put("textures", textures);
            emitter.model(allocatedModelId, model);
        }
        if (modelRef == null || modelRef.isBlank()) {
            return; // material-only item: no pack output (vanilla render)
        }
        // 1.21.4+ item model definition (minecraft.wiki/w/Items_model_definition):
        // the basic single-model case is type "minecraft:model" + "model" — the
        // "reference"/"id" shape is not a valid definition type on any client
        var reference = new LinkedHashMap<String, Object>();
        reference.put("type", "minecraft:model");
        reference.put("model", modelRef.contains(":") ? modelRef : item.id().namespace() + ":" + modelRef);
        var root = new LinkedHashMap<String, Object>();
        root.put("model", reference);
        emitter.itemDefinition(item.id(), root);
    }

    // --- helpers -------------------------------------------------------------

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static String textOrDefault(Map<String, Object> map, String key, String fallback) {
        String value = text(map, key);
        return value != null ? value : fallback;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (Object entry : list) {
            if (entry instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * A {@code behavior:} / {@code mechanics:} section as raw maps (one
     * entry per rule/mechanic); the engine and mechanic registry compile
     * them at apply.
     */
    private static List<Map<String, Object>> behaviorMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var out = new ArrayList<Map<String, Object>>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> mapping) {
                // stringKeyed: bare `on:` loads as a YAML-1.1 boolean key
                out.add(dev.materione.core.document.LosslessYaml.stringKeyed(mapping));
            }
        }
        return out;
    }

    private static Map<String, Object> nestedMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        var out = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }
}
