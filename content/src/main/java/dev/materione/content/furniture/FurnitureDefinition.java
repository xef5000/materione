package dev.materione.content.furniture;

import dev.materione.api.NamespacedId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed furniture definition (runtime-systems §5): a persistent
 * ItemDisplay cluster with interaction/barrier hitboxes, seats, lights,
 * optional storage, a TextDisplay label and timed evolution stages.
 * Offsets are relative to the anchor block's center (x/z) and base (y);
 * barrier and light offsets land on whole block cells. Per-state model
 * cycling is parsed but inert until M7 (parity-reconciliation §3.6).
 */
public record FurnitureDefinition(
        NamespacedId id,
        String model,             // hand-authored model ref, if any
        String texture,           // generated-model layer0 texture, if any
        String displayName,
        double rotationSnap,      // degrees: 45/90/180, or 0 = never rotate
        List<Hitbox> hitboxes,
        List<double[]> seats,
        List<Light> lights,
        int storage,              // slot count, multiple of 9; 0 = none
        String label,
        List<Stage> evolution,
        NamespacedId placedBy,    // item that places this furniture
        List<Map<String, Object>> behavior,
        Map<String, Object> raw) {

    public FurnitureDefinition {
        hitboxes = List.copyOf(hitboxes);
        seats = List.copyOf(seats);
        lights = List.copyOf(lights);
        evolution = List.copyOf(evolution);
        behavior = behavior == null ? List.of() : List.copyOf(behavior);
        raw = Map.copyOf(raw);
    }

    /** One interaction or barrier hitbox. */
    public record Hitbox(String type, double[] offset, double width, double height) {
        public boolean isBarrier() {
            return "barrier".equals(type);
        }
    }

    /** One light block: offset cell + light level 1..15. */
    public record Light(double[] offset, int level) {}

    /** One evolution step: after {@code afterSeconds} the cluster becomes {@code target}. */
    public record Stage(NamespacedId target, long afterSeconds) {}

    /** True when the definition asks for a generated model (auto-model path). */
    public boolean wantsGeneratedModel() {
        return texture != null && !texture.isBlank();
    }

    /**
     * The model reference the compiler and the runtime agree on: the
     * generated {@code <id>_model} on the auto-model path, else the
     * hand-authored ref resolved against the pack namespace.
     */
    public String modelRef() {
        if (wantsGeneratedModel()) {
            return id.namespace() + ":" + id.value() + "_model";
        }
        return model.contains(":") ? model : id.namespace() + ":" + model;
    }

    /** Merged view: raw fields with named accessors on top. */
    public Map<String, Object> merged() {
        var merged = new LinkedHashMap<String, Object>(raw);
        if (displayName != null) {
            merged.put("display_name", displayName);
        }
        return merged;
    }
}
