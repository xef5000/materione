package dev.materione.plugin.runtime.mechanics.builtin;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Stable, builtin-owned keys; addon registrations never supply this namespace. */
final class BuiltinPdc {

    private BuiltinPdc() {}

    static NamespacedKey key(String name) {
        return new NamespacedKey("materione", name);
    }

    static String string(ItemStack stack, String name) {
        return read(stack, name, PersistentDataType.STRING);
    }

    static byte[] bytes(ItemStack stack, String name) {
        return read(stack, name, PersistentDataType.BYTE_ARRAY);
    }

    static int integer(ItemStack stack, String name) {
        Integer value = read(stack, name, PersistentDataType.INTEGER);
        return value == null ? 0 : value;
    }

    private static <P, C> C read(ItemStack stack, String name, PersistentDataType<P, C> type) {
        if (stack == null || stack.getItemMeta() == null) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(key(name), type);
    }

    static void clear(ItemStack stack, String... names) {
        stack.editMeta(meta -> {
            for (String name : names) {
                meta.getPersistentDataContainer().remove(key(name));
            }
        });
    }
}
