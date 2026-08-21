package dev.materione.content.items;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.content.ContentType;
import dev.materione.core.content.PackEmitter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
                                "Vanilla data components (version-aware, platform-gated)", null, null)),
                "1.0.0");
    }

    @Override
    public Optional<Object> parse(
            NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
        var merged = new LinkedHashMap<String, Object>();
        String templateRef = text(definition, "template");
        if (templateRef != null) {
            Optional<Map<String, Object>> base = resolveTemplate(templateRef, templates, new HashSet<>());
            if (base.isEmpty()) {
                return Optional.empty(); // unknown or diamond template — quarantine upstream
            }
            merged.putAll(base.get());
        }
        merged.putAll(definition); // the definition itself wins

        Object material = merged.get("material");
        if (!(material instanceof String materialText) || materialText.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ItemDefinition(
                id,
                materialText,
                templateRef,
                text(merged, "model"),
                text(merged, "texture"),
                textOrDefault(merged, "model_parent", "item/generated"),
                text(merged, "display_name"),
                stringList(merged.get("lore")),
                nestedMap(merged.get("components")),
                merged));
    }

    /** Template chains resolve recursively; a diamond (revisited template) is rejected. */
    private static Optional<Map<String, Object>> resolveTemplate(
            String ref, Map<String, Map<String, Object>> templates, Set<String> visited) {
        if (!visited.add(ref)) {
            return Optional.empty(); // diamond/cycle — validation error per runtime-systems §3
        }
        Map<String, Object> template = templates.get(ref);
        if (template == null) {
            return Optional.empty();
        }
        var merged = new LinkedHashMap<String, Object>();
        String parentRef = text(template, "template");
        if (parentRef != null) {
            Optional<Map<String, Object>> parent = resolveTemplate(parentRef, templates, visited);
            if (parent.isEmpty()) {
                return Optional.empty();
            }
            merged.putAll(parent.get());
        }
        merged.putAll(template);
        return Optional.of(merged);
    }

    @Override
    public void compile(Object definition, NamespacedId allocatedModelId, PackEmitter emitter) {
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
