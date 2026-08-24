package dev.materione.plugin.items;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Hand-out math shared by {@code /materione give} and the content browser:
 * how an amount becomes stacks, and where leftovers go. The split is pure
 * (unit-tested); {@link #deliver} mutates one player's inventory and must
 * run on that player's region thread.
 */
public final class Give {

    /** 36 rows × 64 — beyond this an operator means a script, not a handout. */
    public static final int MAX_AMOUNT = 2304;

    private Give() {}

    /** Parses an amount argument, clamped into {@code [1, MAX_AMOUNT]}; bad input → 1. */
    public static int clampAmount(String raw) {
        try {
            return Math.max(1, Math.min(MAX_AMOUNT, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * {@code amount} items of one type as stack sizes (each in
     * {@code [1, maxSize]}), largest first so inventory merging behaves.
     */
    public static List<Integer> split(int amount, int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
        }
        var sizes = new ArrayList<Integer>();
        int remaining = Math.max(0, amount);
        while (remaining > 0) {
            int take = Math.min(maxSize, remaining);
            sizes.add(take);
            remaining -= take;
        }
        return sizes;
    }

    /**
     * Adds {@code amount} copies of {@code template} to the player's
     * inventory; whatever does not fit drops at their feet. Never throws
     * for full inventories.
     */
    public static void deliver(Player player, ItemStack template, int amount) {
        Location drop = player.getLocation();
        for (int size : split(amount, template.getMaxStackSize())) {
            ItemStack stack = template.clone();
            stack.setAmount(size);
            player.getInventory().addItem(stack).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(drop, leftover));
        }
    }
}
