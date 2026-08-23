package dev.materione.core.runtime;

import dev.materione.api.NamespacedId;
import java.util.List;

/**
 * One furniture definition as the runtime serves it (runtime-systems §5):
 * everything the cluster spawner needs, Bukkit-free. Offsets are relative
 * to the anchor block's center (x/z) and base (y). Immutable; rebuilt
 * wholesale by each apply — reload rebuilds visuals from the CURRENT
 * definition but never rewrites a placed cluster's stored id.
 */
public record RuntimeFurniture(
        NamespacedId id,
        String modelRef,          // resolved model reference for the display item
        String displayName,
        double rotationSnap,      // degrees: 45/90/180, or 0 = never rotate
        List<Hitbox> hitboxes,
        List<double[]> seats,
        List<Light> lights,
        int storage,              // slot count; 0 = none
        String label,
        List<Stage> evolution) {

    public RuntimeFurniture {
        hitboxes = List.copyOf(hitboxes);
        seats = seats.stream().map(double[]::clone).toList();
        lights = List.copyOf(lights);
        evolution = List.copyOf(evolution);
    }

    /** One interaction or barrier hitbox. */
    public record Hitbox(String type, double[] offset, double width, double height) {
        public boolean isBarrier() {
            return "barrier".equals(type);
        }
    }

    /** One light block: offset cell + level 1..15. */
    public record Light(double[] offset, int level) {}

    /** One evolution step: after {@code afterSeconds} the cluster becomes {@code target}. */
    public record Stage(NamespacedId target, long afterSeconds) {}
}
