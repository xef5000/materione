package dev.materione.content.items;

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
        return new SelfDescription(
                "Item",
                "A custom item: identity, appearance (item model), data components, behavior",
                List.of(
                        new ParameterSchema("material", ParameterType.STRING, true, null,
                                "Vanilla base material (e.g. NETHERITE_SWORD)", null, null),
                        new ParameterSchema("template", ParameterType.REFERENCE, false, null,
                                "Template this item inherits from", null, null),
                        new ParameterSchema("model", ParameterType.REFERENCE, false, null,
                                "Hand-authored model reference (assets/<ns>/models/<id>.json)", null, null),
                        new ParameterSchema("texture", ParameterType.REFERENCE, false, null,
                                "Texture for the generated model (auto-model path)", null, null),
                        new ParameterSchema("model_parent", ParameterType.STRING, false, "item/generated",
                                "Parent for the generated model", null, null),
                        new ParameterSchema("display_name", ParameterType.STRING, false, null,
                                "Item display name", null, null),
                        new ParameterSchema("lore", ParameterType.LIST, false, null,
                                "Lore lines", null, null),
                        new ParameterSchema("components", ParameterType.RAW, false, null,
                                "Vanilla data components (version-aware, platform-gated)", null, null),
                        new ParameterSchema("mechanics", ParameterType.LIST, false, null,
                                "Mechanic bundle: one map per mechanic ({name: parameters})", null, null)),
                "1.0.0");
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
                merged));
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
        var reference = new LinkedHashMap<String, Object>();
        reference.put("type", "reference");
        reference.put("id", modelRef.contains(":") ? modelRef : item.id().namespace() + ":" + modelRef);
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
