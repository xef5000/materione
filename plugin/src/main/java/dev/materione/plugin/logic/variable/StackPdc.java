package dev.materione.plugin.logic.variable;

import dev.materione.platform.items.MaterioneStacks;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;

/**
 * How the item-variable store reaches one stack's own persistent data (logic.md §38). The seam exists so the store's
 * logic (typed keys, the default-clears rule, the identity check, the owner's thread) is unit-tested without a
 * server: production wraps a Bukkit {@link ItemStack}, a test supplies a map-backed container.
 */
interface StackPdc {

    /** A read view of the stack's data, or empty when the stack no longer exists (air, an amount of zero). */
    Optional<PersistentDataContainer> view();

    /**
     * Applies {@code edit} to the stack's data in place.
     *
     * @return false when the stack no longer exists or could not be edited
     */
    boolean edit(Consumer<PersistentDataContainer> edit);

    /**
     * The stack's Materione identity as text, blank for a vanilla item. A run that resumes later compares it with
     * the identity it started with, so a handle never writes into a different item that now occupies the object.
     */
    String identity();

    /** The data of a live Bukkit stack. A stack handed out by an event or an inventory is a mirror, so edits land. */
    static StackPdc of(ItemStack stack) {
        return new StackPdc() {
            @Override
            public Optional<PersistentDataContainer> view() {
                if (stack.getType().isAir() || stack.getAmount() <= 0) {
                    return Optional.empty();
                }
                var meta = stack.getItemMeta();
                return meta == null ? Optional.empty() : Optional.of(meta.getPersistentDataContainer());
            }

            @Override
            public boolean edit(Consumer<PersistentDataContainer> edit) {
                if (stack.getType().isAir() || stack.getAmount() <= 0) {
                    return false;
                }
                return stack.editMeta(meta -> edit.accept(meta.getPersistentDataContainer()));
            }

            @Override
            public String identity() {
                return MaterioneStacks.identityOf(stack).map(Object::toString).orElse("");
            }
        };
    }
}
