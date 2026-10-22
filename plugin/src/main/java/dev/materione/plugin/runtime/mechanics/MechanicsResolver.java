package dev.materione.plugin.runtime.mechanics;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryKey;
import dev.materione.api.mechanics.Mechanic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Resolves an item's raw {@code mechanics:} maps against the mechanics
 * registry at apply time: unknown names and parameter-shape violations are
 * reported through {@code reject} (diagnostic MTR-0027) and skipped —
 * fail-soft, the item keeps its remaining mechanics (D6). Pure over its
 * inputs; the plugin service supplies the diagnostics sink.
 */
public final class MechanicsResolver {

    /** The registry key every mechanic registers under (public addon seam). */
    public static final RegistryKey REGISTRY_KEY = dev.materione.api.registry.RegistryKeys.MECHANICS;

    private final Optional<Registry<Mechanic>> registry;

    public MechanicsResolver(Optional<Registry<Mechanic>> registry) {
        this.registry = registry;
    }

    /**
     * Resolves one item's declarations. {@code reject} receives
     * {@code (mechanicName, reason)} per skipped declaration.
     */
    public List<ActiveMechanic> resolve(
            NamespacedId itemId,
            List<Map<String, Object>> declarations,
            BiConsumer<String, String> reject) {
        var active = new ArrayList<ActiveMechanic>();
        if (declarations.isEmpty()) {
            return active;
        }
        if (registry.isEmpty()) {
            return active; // no mechanic surface registered — nothing to resolve against
        }
        for (Map<String, Object> declaration : declarations) {
            for (Map.Entry<String, Object> entry : declaration.entrySet()) {
                String name = entry.getKey();
                if (!known(name)) {
                    reject.accept(name, "unknown mechanic");
                    continue;
                }
                @SuppressWarnings("unchecked")
                var params = entry.getValue() instanceof Map<?, ?> mapping
                        ? (Map<String, Object>) mapping : Map.<String, Object>of();
                String problem = validate(name, params);
                if (problem != null) {
                    reject.accept(name, problem);
                    continue;
                }
                active.add(new ActiveMechanic(name, params));
            }
        }
        return List.copyOf(active);
    }

    private boolean known(String name) {
        // matched on the id's value part: built-ins register as materione:<name>,
        // addons may re-register the same mechanic name under their namespace
        return registry.orElseThrow().entries().stream()
                .anyMatch(entry -> entry.id().value().equals(name));
    }

    /**
     * Parameter validation per native mechanic — the same checks the
     * runtime listeners rely on. Returns null when the parameters are
     * usable.
     */
    public static String validate(String name, Map<String, Object> params) {
        return switch (name) {
            case "food" -> {
                int nutrition = ActiveMechanic.intParam(params, "nutrition", -1);
                if (nutrition < 0 || nutrition > 20) {
                    yield "nutrition must be 0..20";
                }
                double saturation = ActiveMechanic.doubleParam(params, "saturation", -1);
                if (saturation < 0 || saturation > 40) {
                    yield "saturation must be 0..40";
                }
                yield effectsValid(params);
            }
            case "durability" -> ActiveMechanic.intParam(params, "max", -1) > 0
                    ? null : "max must be a positive integer";
            case "backpack" -> {
                int rows = ActiveMechanic.intParam(params, "rows", -1);
                yield rows >= 1 && rows <= 6 ? null : "rows must be 1..6";
            }
            case "commands" -> commandsValid(params);
            case "soulbound" -> null; // no parameters
            default -> null; // addon mechanic: shape is its own business
        };
    }

    private static String effectsValid(Map<String, Object> params) {
        Object value = params.get("effects");
        if (!(value instanceof List<?> list)) {
            return null;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> effect)
                    || !(effect.get("type") instanceof String type) || type.isBlank()) {
                return "each effect needs {type, amplifier?, duration_ticks?}";
            }
        }
        return null;
    }

    private static String commandsValid(Map<String, Object> params) {
        for (String key : List.of("on_right_click", "on_left_click")) {
            Object value = params.get(key);
            if (value != null && !(value instanceof List<?> list
                    && list.stream().allMatch(String.class::isInstance))) {
                return key + " must be a list of command strings";
            }
        }
        return null;
    }
}
