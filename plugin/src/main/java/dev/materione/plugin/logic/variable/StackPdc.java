package dev.materione.plugin.logic.variable;

import dev.materione.platform.items.MaterioneStacks;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
     * Applies {@code edit} to the stack's item meta in place (name, lore, model, damage), for the stack-editing verb.
     * A stack double that has no item meta cannot, and says so with false.
     */
    default boolean editMeta(Consumer<org.bukkit.inventory.meta.ItemMeta> edit) {
        return false;
    }

    /**
     * The stack's Materione identity as text, blank for a vanilla item. A run that resumes later compares it with
     * the identity it started with, so a handle never writes into a different item that now occupies the object.
     */
    String identity();

    /** The expression-visible facts of the live stack, or an empty map when it is gone. */
    default Map<String, Object> itemView() {
        return Map.of();
    }

    /** The data of a live Bukkit stack. A stack handed out by an event or an inventory is a mirror, so edits land. */
    static StackPdc of(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return EMPTY;
        }
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
            public boolean editMeta(Consumer<org.bukkit.inventory.meta.ItemMeta> edit) {
                if (stack.getType().isAir() || stack.getAmount() <= 0) {
                    return false;
                }
                return stack.editMeta(edit);
            }

            @Override
            public String identity() {
                return MaterioneStacks.identityOf(stack).map(Object::toString).orElse("");
            }

            @Override
            public Map<String, Object> itemView() {
                if (stack.getType().isAir() || stack.getAmount() <= 0) {
                    return Map.of();
                }
                var values = new LinkedHashMap<String, Object>();
                values.put("id", identity());
                values.put("material", stack.getType().name().toLowerCase(Locale.ROOT));
                values.put("amount", stack.getAmount());
                var meta = stack.getItemMeta();
                if (meta != null) {
                    var data = meta.getPersistentDataContainer();
                    var used = data.get(new org.bukkit.NamespacedKey("materione", "durability-used"),
                            org.bukkit.persistence.PersistentDataType.INTEGER);
                    var max = data.get(new org.bukkit.NamespacedKey("materione", "durability-max"),
                            org.bukkit.persistence.PersistentDataType.INTEGER);
                    if (used != null && max != null) {
                        values.put("durability_used", used);
                        values.put("durability_max", max);
                    }
                }
                return Map.copyOf(values);
            }
        };
    }

    StackPdc EMPTY = new StackPdc() {
        @Override
        public Optional<PersistentDataContainer> view() {
            return Optional.empty();
        }

        @Override
        public boolean edit(Consumer<PersistentDataContainer> edit) {
            return false;
        }

        @Override
        public String identity() {
            return "";
        }
    };
}
