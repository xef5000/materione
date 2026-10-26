package dev.materione.content.furniture;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.content.ResourcePresentation;
import dev.materione.content.Templates;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The furniture content type (M2, runtime-systems §5). Furniture is
 * entity-carried: nothing is allocated and no blockstates are emitted —
 * the only pack output is the generated display model when a texture is
 * given. Rotation snaps are limited to 45/90/180 or disabled (barrier
 * cells can only rotate in 90° steps); evolution targets and the
 * {@code item:} placement link resolve like every other reference.
 */
public final class FurnitureType implements ContentType {

    @Override
    public String name() {
        return "furniture";
    }

    @Override
    public String directory() {
        // plural like items/ and blocks/ — ParseStage derives the content
        // type by stripping the trailing "s"
        return "furnitures";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Furniture",
                "A display-entity cluster: hitboxes, seats, lights, storage, evolution",
                List.of(
                        new ParameterSchema("display_name", ParameterType.STRING, false, null,
                                "Display name (storage title)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Display name", "identity", 0, false, "text", "",
                                        "Oak chair")),
                        new ParameterSchema("texture", ParameterType.REFERENCE, false, null,
                                "Texture for the generated model (auto-model path)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Texture", "appearance", 10, false, "texture", "texture",
                                        "furniture/oak_chair")),
                        new ParameterSchema("model", ParameterType.REFERENCE, false, null,
                                "Hand-authored model reference (assets/<ns>/models/<id>.json)",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "3D model", "appearance", 20, false, "model", "model", "")),
                        new ParameterSchema("label", ParameterType.STRING, false, null,
                                "TextDisplay label above the furniture", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Floating label", "appearance", 30, false, "text", "",
                                        "Sit down")),
                        new ParameterSchema("rotation", ParameterType.DECIMAL, false, "90",
                                "Rotation snap in degrees: 45, 90, 180, or 0 to disable",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Rotation snap", "placement", 10, false, "number", "",
                                        "90")),
                        new ParameterSchema("hitboxes", ParameterType.LIST, false, null,
                                "Interaction/barrier hitboxes: {type, offset: [x,y,z], width, "
                                        + "height}",
                                null,
                                List.of(
                                        new ParameterSchema("type", ParameterType.ENUM, true,
                                                "interaction",
                                                "interaction: clickable. barrier: solid.",
                                                List.of("interaction", "barrier"), null),
                                        new ParameterSchema("offset", ParameterType.LIST, false,
                                                "[0, 0, 0]", "Offset from the placed origin",
                                                null, null),
                                        new ParameterSchema("width", ParameterType.DECIMAL, false,
                                                "1", "Width in blocks", null, null),
                                        new ParameterSchema("height", ParameterType.DECIMAL, false,
                                                "1", "Height in blocks", null, null)))
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Hitboxes", "placement", 20, false, "structured-list", "",
                                        "")),
                        new ParameterSchema("seats", ParameterType.LIST, false, null,
                                "Seat mount points: {offset: [x,y,z]}", null,
                                List.of(new ParameterSchema("offset", ParameterType.LIST, false,
                                        "[0, 0, 0]", "Where the player sits", null, null)))
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Seats", "placement", 30, false, "structured-list", "",
                                        "")),
                        new ParameterSchema("lights", ParameterType.LIST, false, null,
                                "Light blocks: {offset: [x,y,z], level}", null,
                                List.of(
                                        new ParameterSchema("offset", ParameterType.LIST, false,
                                                "[0, 0, 0]", "Where the light sits", null, null),
                                        new ParameterSchema("level", ParameterType.INTEGER, false,
                                                "15", "Light level 0..15", null, null)))
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Lights", "placement", 40, false, "structured-list", "",
                                        "")),
                        new ParameterSchema("storage", ParameterType.INTEGER, false, null,
                                "Storage size in slots (multiple of 9, max 54)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Storage slots", "gameplay", 10, false, "number", "",
                                        "27")),
                        new ParameterSchema("evolution", ParameterType.LIST, false, null,
                                "Timed stages: {target: furniture-id, after: seconds}", null,
                                List.of(
                                        new ParameterSchema("target", ParameterType.REFERENCE,
                                                true, null, "Furniture it becomes", null, null),
                                        new ParameterSchema("after", ParameterType.INTEGER, true,
                                                "60", "Seconds before it changes", null, null)))
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Evolution stages", "gameplay", 20, false,
                                        "structured-list", "", "")),
                        new ParameterSchema("item", ParameterType.REFERENCE, false, null,
                                "Optional custom placement item; auto-generated from this "
                                        + "furniture when omitted",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Placement item", "gameplay", 30, true, "reference",
                                        "item", "")),
                        new ParameterSchema("behavior", ParameterType.LIST, false, null,
                                "ECA rules fired on place/click", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Behaviour rules", "behavior", 10, false,
                                        "behavior-rules", "", ""))),
                "1.0.0");
    }

    @Override
    public Optional<Object> parse(
            NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
        var merged = Templates.merge(definition, id.namespace(), templates);
        if (merged == null) {
            return Optional.empty(); // unknown or diamond template — quarantine upstream
        }
        String texture = Templates.text(merged, "texture");
        String model = Templates.text(merged, "model");
        if (texture == null && model == null) {
            return Optional.empty(); // nothing to render
        }
        Double rotation = number(merged.get("rotation"));
        double snap = rotation == null ? 90.0 : rotation;
        if (snap != 0 && snap != 45 && snap != 90 && snap != 180) {
            return Optional.empty();
        }
        Integer storage = storage(merged.get("storage"));
        if (storage == null) {
            return Optional.empty();
        }
        List<FurnitureDefinition.Hitbox> hitboxes = hitboxes(merged.get("hitboxes"));
        if (hitboxes == null) {
            return Optional.empty();
        }
        List<double[]> seats = offsets(merged.get("seats"));
        List<FurnitureDefinition.Light> lights = lights(merged.get("lights"));
        if (lights == null) {
            return Optional.empty();
        }
        String itemRef = Templates.text(merged, "item");
        NamespacedId placedBy = itemRef == null
                ? null
                : itemRef.contains(":")
                        ? NamespacedId.parse(itemRef)
                        : NamespacedId.of(id.namespace(), itemRef);
        List<FurnitureDefinition.Stage> evolution = evolution(id.namespace(), merged.get("evolution"));
        if (evolution == null) {
            return Optional.empty();
        }
        return Optional.of(new FurnitureDefinition(
                id,
                model,
                texture,
                Templates.text(merged, "display_name"),
                snap,
                hitboxes,
                seats,
                lights,
                storage,
                Templates.text(merged, "label"),
                evolution,
                placedBy,
                behaviorMaps(merged.get("behavior")),
                merged));
    }

    /**
     * Emits the display surface: the generated model, plus an item
     * definition at the model ref. The cluster's ItemDisplay carries
     * {@code item_model: <modelRef>} and the client resolves that against
     * {@code assets/<ns>/items/<id>.json} — without the definition the
     * display renders the missing model. The definition id equals the
     * model id (items/ and models/ are different directories), for
     * generated and hand-authored models alike.
     */
    @Override
    public void compile(
            Object definition, NamespacedId allocatedModelId, PackEmitter emitter, dev.materione.api.content.Allocations allocations) {
        if (!(definition instanceof FurnitureDefinition furniture)) {
            return;
        }
        String modelRef;
        if (furniture.wantsGeneratedModel()) {
            emitter.model(allocatedModelId, generatedModel(furniture));
            modelRef = allocatedModelId.toString();
        } else if (furniture.model() != null && !furniture.model().isBlank()) {
            modelRef = furniture.modelRef();
        } else {
            return; // no appearance surface
        }
        var reference = new LinkedHashMap<String, Object>();
        reference.put("type", "minecraft:model");
        reference.put("model", modelRef);
        var root = new LinkedHashMap<String, Object>();
        root.put("model", reference);
        emitter.itemDefinition(NamespacedId.parse(modelRef), root);
        if (furniture.placedBy() == null) {
            // the auto placement item (runtime-systems §13): a definition at
            // the furniture's own id so the synthesized placement item renders
            // the model; dropped at merge time when a real item resource owns
            // the id (the implicit same-id link uses that item's definition)
            emitter.syntheticItemDefinition(furniture.id(), root);
        }
    }

    @Override
    public Optional<ResourcePresentation> presentation(Object parsedDefinition) {
        if (!(parsedDefinition instanceof FurnitureDefinition furniture)) {
            return Optional.empty();
        }
        return Optional.of(new ResourcePresentation(
                furniture.displayName(), null, furniture.texture(), furniture.model(), null,
                generatedModel(furniture)));
    }

    /**
     * The model the auto-model path emits, or null for hand-authored models;
     * compile and the editor preview both read it.
     */
    static Map<String, Object> generatedModel(FurnitureDefinition furniture) {
        if (!furniture.wantsGeneratedModel()) {
            return null;
        }
        String texture = furniture.texture().contains(":")
                ? furniture.texture()
                : furniture.id().namespace() + ":" + furniture.texture();
        var model = new LinkedHashMap<String, Object>();
        model.put("parent", "block/cube_all");
        model.put("textures", Map.<String, Object>of("all", texture));
        return model;
    }

    // --- parse helpers ---------------------------------------------------------

    private static Double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Integer storage(Object value) {
        if (value == null) {
            return 0;
        }
        Double parsed = number(value);
        if (parsed == null || parsed != Math.floor(parsed)
                || parsed < 0 || parsed > 54 || parsed % 9 != 0) {
            return null;
        }
        return parsed.intValue();
    }

    private static List<FurnitureDefinition.Hitbox> hitboxes(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var out = new ArrayList<FurnitureDefinition.Hitbox>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> mapping)) {
                return null; // malformed hitbox rejects the definition
            }
            String type = text(mapping.get("type"));
            if (!"interaction".equals(type) && !"barrier".equals(type)) {
                return null;
            }
            double[] offset = offset(mapping.get("offset"));
            if (offset == null) {
                return null;
            }
            Double width = number(orDefault(mapping.get("width"), type.equals("barrier") ? 1 : 0.6));
            Double height = number(orDefault(mapping.get("height"), type.equals("barrier") ? 1 : 1.0));
            if (width == null || height == null || width <= 0 || height <= 0) {
                return null;
            }
            out.add(new FurnitureDefinition.Hitbox(type, offset, width, height));
        }
        return out;
    }

    private static List<double[]> offsets(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var out = new ArrayList<double[]>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> mapping) {
                double[] offset = offset(mapping.get("offset"));
                if (offset == null) {
                    return out; // best effort: drop malformed seats silently
                }
                out.add(offset);
            } else if (entry instanceof List<?> coords && coords.size() == 3) {
                double[] offset = offset(coords);
                if (offset != null) {
                    out.add(offset);
                }
            }
        }
        return out;
    }

    private static List<FurnitureDefinition.Light> lights(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var out = new ArrayList<FurnitureDefinition.Light>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> mapping)) {
                return null;
            }
            double[] offset = offset(mapping.get("offset"));
            Double level = number(mapping.get("level"));
            if (offset == null || level == null || level < 1 || level > 15) {
                return null;
            }
            out.add(new FurnitureDefinition.Light(offset, level.intValue()));
        }
        return out;
    }

    private static List<FurnitureDefinition.Stage> evolution(String namespace, Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var out = new ArrayList<FurnitureDefinition.Stage>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> mapping)) {
                return null;
            }
            String target = text(mapping.get("target"));
            Double after = number(mapping.get("after"));
            if (target == null || after == null || after < 0) {
                return null;
            }
            NamespacedId targetId = target.contains(":")
                    ? NamespacedId.parse(target)
                    : NamespacedId.of(namespace, target);
            out.add(new FurnitureDefinition.Stage(targetId, after.longValue()));
        }
        return out;
    }

    private static double[] offset(Object value) {
        if (value instanceof List<?> coords && coords.size() == 3) {
            var out = new double[3];
            for (int i = 0; i < 3; i++) {
                Double v = coords.get(i) instanceof Number n ? n.doubleValue() : null;
                if (v == null) {
                    return null;
                }
                out[i] = v;
            }
            return out;
        }
        return null;
    }

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

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static Object orDefault(Object value, Object fallback) {
        return value == null ? fallback : value;
    }
}
