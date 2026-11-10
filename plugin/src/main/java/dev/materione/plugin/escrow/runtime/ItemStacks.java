package dev.materione.plugin.escrow.runtime;

import dev.materione.plugin.escrow.model.StackBytes;
import org.bukkit.inventory.ItemStack;

/**
 * {@link Stacks} over Bukkit's {@link ItemStack}: the platform's own byte form (F13, the one the old journal and the
 * account both use), so a stack comes back exactly as it went in, data version included.
 */
public final class ItemStacks implements Stacks<ItemStack> {

    /** The shared instance: stateless. */
    public static final ItemStacks INSTANCE = new ItemStacks();

    private ItemStacks() {}

    @Override
    public boolean empty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    @Override
    public int amount(ItemStack stack) {
        return empty(stack) ? 0 : stack.getAmount();
    }

    @Override
    public int maxStack(ItemStack stack) {
        return empty(stack) ? 64 : stack.getMaxStackSize();
    }

    @Override
    public boolean similar(ItemStack a, ItemStack b) {
        return !empty(a) && !empty(b) && a.isSimilar(b);
    }

    @Override
    public ItemStack withAmount(ItemStack stack, int amount) {
        if (amount <= 0 || empty(stack)) {
            return null;
        }
        ItemStack copy = stack.clone();
        copy.setAmount(amount);
        return copy;
    }

    @Override
    public StackBytes encode(ItemStack stack) {
        return StackBytes.of(stack.serializeAsBytes());
    }

    @Override
    public ItemStack decode(StackBytes bytes) {
        return ItemStack.deserializeBytes(bytes.bytes());
    }
}
