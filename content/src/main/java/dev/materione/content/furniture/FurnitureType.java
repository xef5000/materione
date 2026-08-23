package dev.materione.content.furniture;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.content.ContentType;
import dev.materione.core.content.PackEmitter;
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
                        new ParameterSchema("model", ParameterType.REFERENCE, false, null,
                                "Hand-authored model reference (assets/<ns>/models/<id>.json)", null, null),
                        new ParameterSchema("texture", ParameterType.REFERENCE, false, null,
                                "Texture for the generated model (auto-model path)", null, null),
                        new ParameterSchema("display_name", ParameterType.STRING, false, null,
                                "Display name (storage title)", null, null),
                        new ParameterSchema("rotation", ParameterType.DECIMAL, false, "90",
                                "Rotation snap in degrees: 45, 90, 180, or 0 to disable", null, null),
                        new ParameterSchema("hitboxes", ParameterType.LIST, false, null,
                                "Interaction/barrier hitboxes: {type, offset: [x,y,z], width, height}", null, null),
                        new ParameterSchema("seats", ParameterType.LIST, false, null,
                                "Seat mount points: {offset: [x,y,z]}", null, null),
                        new ParameterSchema("lights", ParameterType.LIST, false, null,
                                "Light blocks: {offset: [x,y,z], level}", null, null),
                        new ParameterSchema("storage", ParameterType.INTEGER, false, null,
                                "Storage size in slots (multiple of 9, max 54)", null, null),
                        new ParameterSchema("label", ParameterType.STRING, false, null,
                                "TextDisplay label above the furniture", null, null),
                        new ParameterSchema("evolution", ParameterType.LIST, false, null,
                                "Timed stages: {target: furniture-id, after: seconds}", null, null),
                        new ParameterSchema("item", ParameterType.REFERENCE, false, null,
                                "Item that places this furniture (the placement link)", null, null),
                        new ParameterSchema("behavior", ParameterType.LIST, false, null,
                                "ECA rules fired on place/click", null, null)),
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

    /** Emits the generated display model; furniture has no blockstate surface. */
    @Override
    public void compile(
            Object definition, NamespacedId allocatedModelId, PackEmitter emitter, dev.materione.core.content.Allocations allocations) {
        if (!(definition instanceof FurnitureDefinition furniture) || !furniture.wantsGeneratedModel()) {
            return;
        }
        String texture = furniture.texture().contains(":")
                ? furniture.texture()
                : furniture.id().namespace() + ":" + furniture.texture();
        var model = new LinkedHashMap<String, Object>();
        model.put("parent", "block/cube_all");
        model.put("textures", Map.<String, Object>of("all", texture));
        emitter.model(allocatedModelId, model);
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
