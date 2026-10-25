package dev.materione.api.mechanics;

import dev.materione.api.NamespacedId;
import java.util.List;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/**
 * An immutable event-entry snapshot, scoped to one registered mechanic id.
 * Retaining it across a reload retains the old bindings intentionally.
 */
public interface MechanicLookup {
    /** The first matching binding in declaration order; use allOnItem for repeatable mechanics. */
    <T extends MechanicInstance> Optional<T> onItem(NamespacedId item, Class<T> type);

    <T extends MechanicInstance> Optional<T> onItem(ItemStack stack, Class<T> type);

    /** All matching bindings, immutable and in declaration order. */
    default <T extends MechanicInstance> List<T> allOnItem(NamespacedId item, Class<T> type) {
        return onItem(item, type).stream().toList();
    }

    default <T extends MechanicInstance> List<T> allOnItem(ItemStack stack, Class<T> type) {
        return onItem(stack, type).stream().toList();
    }
}
