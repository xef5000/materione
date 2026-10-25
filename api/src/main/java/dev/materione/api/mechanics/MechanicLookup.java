package dev.materione.api.mechanics;

import dev.materione.api.NamespacedId;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/**
 * An immutable event-entry snapshot, scoped to one registered mechanic id.
 * Retaining it across a reload retains the old bindings intentionally.
 */
public interface MechanicLookup {
    <T extends MechanicInstance> Optional<T> onItem(NamespacedId item, Class<T> type);

    <T extends MechanicInstance> Optional<T> onItem(ItemStack stack, Class<T> type);
}
