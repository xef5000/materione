package dev.materione.plugin.gui.input;

import dev.materione.plugin.gui.GuiMenu;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Moves the player's items out of a window's input slots (logic.md §6.5, §25). The items are the player's:
 * a window never keeps them. They go back to the player's inventory, and whatever does not fit drops at
 * their feet, so nothing is deleted unless a flow says so (the {@code gui.slot_item} clear).
 *
 * <p>Which slots are returned is the window's own record of its input slots (set when it was shown), never
 * a live lookup: a display item is never handed to the player, whatever the GUI has become since. Every
 * method is idempotent: a slot emptied here is empty for the next caller.
 */
public final class InputReturns {

    private InputReturns() {}

    /** Gives every item in the window's input slots back to {@code player} and empties those slots. */
    public static void release(Player player, GuiMenu menu) {
        if (player == null) {
            return;
        }
        for (ItemStack item : take(menu).values()) {
            giveBack(player, item);
        }
    }

    /**
     * Removes and returns the items in the window's input slots, by slot. The slots are left empty; the
     * caller puts the items somewhere (a new window, or the player).
     */
    public static Map<Integer, ItemStack> take(GuiMenu menu) {
        Inventory top = menu.getInventory();
        var out = new TreeMap<Integer, ItemStack>();
        for (int slot : menu.inputSlots()) {
            ItemStack item = top.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                out.put(slot, item);
                top.setItem(slot, null);
            }
        }
        return out;
    }

    /** Gives one stack to the player: their inventory first, and whatever does not fit drops at their feet. */
    public static void giveBack(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }
}
