package dev.materione.plugin.runtime.mechanics;

import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;

/**
 * The five M2 native mechanics. Each is identity + self-description only —
 * the runtime wiring lives in {@link MechanicsService} listeners, which
 * resolve the applied shadow at event entry. Registered through the same
 * registry path addons use, so the web editor renders their forms from
 * these schemas alone.
 */
public final class NativeMechanics {

    /** Runs console/player commands on item clicks with {@code %player%} substitution. */
    public static final class Commands implements dev.materione.api.mechanics.Mechanic {
        @Override
        public String name() {
            return "commands";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription(
                    "Commands",
                    "Run commands when the item is clicked",
                    List.of(
                            new ParameterSchema("on_right_click", ParameterType.LIST, false, null,
                                    "Commands on right-click (%player% substituted)", null, null),
                            new ParameterSchema("on_left_click", ParameterType.LIST, false, null,
                                    "Commands on left-click", null, null),
                            new ParameterSchema("as_player", ParameterType.BOOLEAN, false, "false",
                                    "Run as the clicking player instead of the console", null, null)),
                    "2.0.0");
        }
    }

    /** The item survives death: stashed into player PDC and returned on respawn. */
    public static final class Soulbound implements dev.materione.api.mechanics.Mechanic {
        @Override
        public String name() {
            return "soulbound";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription(
                    "Soulbound",
                    "The item stays with its owner through death",
                    List.of(), "2.0.0");
        }
    }

    /**
     * Makes the stack edible: the food component is applied at build time
     * (base material must be edible in 1.21.4 — the consumable component
     * surface does not exist in this API yet), consume-side potion effects
     * ride the listener.
     */
    public static final class Food implements dev.materione.api.mechanics.Mechanic {
        @Override
        public String name() {
            return "food";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription(
                    "Food",
                    "Custom nutrition, saturation and consume effects",
                    List.of(
                            new ParameterSchema("nutrition", ParameterType.INTEGER, true, null,
                                    "Hunger points restored (0..20)", null, null),
                            new ParameterSchema("saturation", ParameterType.DECIMAL, true, null,
                                    "Saturation restored (0..40)", null, null),
                            new ParameterSchema("can_always_eat", ParameterType.BOOLEAN, false, "false",
                                    "Edible even at full hunger", null, null),
                            new ParameterSchema("effects", ParameterType.LIST, false, null,
                                    "Potion effects on consume: {type, amplifier?, duration_ticks?}", null, null)),
                    "2.0.0");
        }
    }

    /** Per-instance durability counter in stack PDC; vanilla damage events are cancelled. */
    public static final class Durability implements dev.materione.api.mechanics.Mechanic {
        @Override
        public String name() {
            return "durability";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription(
                    "Durability",
                    "Custom durability independent of the base material",
                    List.of(new ParameterSchema("max", ParameterType.INTEGER, true, null,
                            "Uses before the item breaks", null, null)), "2.0.0");
        }
    }

    /** Right-click opens a persistent container serialized inside the item's own PDC. */
    public static final class Backpack implements dev.materione.api.mechanics.Mechanic {
        @Override
        public String name() {
            return "backpack";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription(
                    "Backpack",
                    "A container that travels inside the item",
                    List.of(
                            new ParameterSchema("rows", ParameterType.INTEGER, true, null,
                                    "Inventory rows (1..6)", null, null),
                            new ParameterSchema("title", ParameterType.STRING, false, "Backpack",
                                    "Inventory title", null, null)),
                    "2.0.0");
        }
    }

    private NativeMechanics() {}

    /** All built-in mechanic entries in registration order. */
    public static List<dev.materione.api.mechanics.Mechanic> all() {
        return List.of(new Commands(), new Soulbound(), new Food(), new Durability(), new Backpack());
    }
}
