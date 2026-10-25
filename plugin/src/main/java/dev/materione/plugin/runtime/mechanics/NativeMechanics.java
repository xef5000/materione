package dev.materione.plugin.runtime.mechanics;

import dev.materione.api.mechanics.Mechanic;
import dev.materione.plugin.runtime.mechanics.builtin.BackpackMechanic;
import dev.materione.plugin.runtime.mechanics.builtin.CommandsMechanic;
import dev.materione.plugin.runtime.mechanics.builtin.DurabilityMechanic;
import dev.materione.plugin.runtime.mechanics.builtin.FoodMechanic;
import dev.materione.plugin.runtime.mechanics.builtin.SoulboundMechanic;
import java.util.List;

/** Built-ins composed through the same public mechanic contract addons implement. */
public final class NativeMechanics {

    private NativeMechanics() {}

    /** All built-in mechanic entries in registration order. */
    public static List<Mechanic> all() {
        return List.of(
                new CommandsMechanic(), new SoulboundMechanic(), new FoodMechanic(),
                new DurabilityMechanic(), new BackpackMechanic());
    }
}
