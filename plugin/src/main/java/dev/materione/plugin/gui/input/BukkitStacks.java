package dev.materione.plugin.gui.input;

import dev.materione.api.NamespacedId;
import dev.materione.platform.items.MaterioneStacks;
import java.util.Locale;
import org.bukkit.inventory.ItemStack;

/** The Bukkit edge of the input rules: reads a real stack into a {@link StackView} and compares stacks. */
public final class BukkitStacks {

    private BukkitStacks() {}

    /** The view of a stack; an empty or missing stack reads as {@link StackView#EMPTY}. */
    public static StackView view(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return StackView.EMPTY;
        }
        return new StackView(idOf(stack), stack.getAmount(), stack.getMaxStackSize());
    }

    /**
     * The identity an author writes: the Materione id the stack carries, or {@code minecraft:<material>}.
     */
    public static String idOf(ItemStack stack) {
        return MaterioneStacks.identityOf(stack)
                .map(NamespacedId::toString)
                .orElse("minecraft:" + stack.getType().name().toLowerCase(Locale.ROOT));
    }

    /** Whether two stacks may merge: both hold something and they are similar (same item, same data). */
    public static boolean similar(ItemStack a, ItemStack b) {
        return a != null && b != null && !a.getType().isAir() && !b.getType().isAir() && a.isSimilar(b);
    }

    /** A copy of {@code stack} with {@code amount} items; null when the amount is zero. */
    public static ItemStack withAmount(ItemStack stack, int amount) {
        if (amount <= 0) {
            return null;
        }
        ItemStack copy = stack.clone();
        copy.setAmount(amount);
        return copy;
    }
}
