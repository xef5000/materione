package dev.materione.plugin.escrow.runtime;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * {@link Live} over a Bukkit player and one window's top inventory. Every call runs on the player's own thread (the
 * region's, on Folia): it is the one owner-thread step of gui-input-durability.md §5.6, made of ordinary inventory
 * calls.
 */
public final class BukkitLive implements Live<ItemStack> {

    private final Player player;
    private final Inventory top;

    /**
     * @param top the window's own inventory; null when the step touches the player's inventory and cursor only
     *     (join recovery, a claim)
     */
    public BukkitLive(Player player, Inventory top) {
        this.player = player;
        this.top = top;
    }

    @Override
    public ItemStack top(int slot) {
        return top == null ? null : top.getItem(slot);
    }

    @Override
    public void top(int slot, ItemStack stack) {
        if (top != null) {
            top.setItem(slot, stack);
        }
    }

    @Override
    public ItemStack cursor() {
        return player.getItemOnCursor();
    }

    @Override
    public void cursor(ItemStack stack) {
        player.setItemOnCursor(stack);
    }

    @Override
    public ItemStack bag(int index) {
        return player.getInventory().getItem(index);
    }

    @Override
    public void bag(int index, ItemStack stack) {
        player.getInventory().setItem(index, stack);
    }
}
