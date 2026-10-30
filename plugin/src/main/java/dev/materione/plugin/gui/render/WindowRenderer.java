package dev.materione.plugin.gui.render;

import dev.materione.platform.items.MaterioneStacks;
import dev.materione.plugin.gui.RuntimeGuis;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Writes a live GUI's slot table into a window's inventory: the items come from the item factory
 * (gui.md §5). This is the one place a {@link RuntimeGuis.Live} becomes stacks, so the dynamic-slot
 * work (a per-session render with {@code visible_if}, templates and a diff) replaces this class and
 * nothing else on the open and refresh paths.
 */
public final class WindowRenderer {

    private WindowRenderer() {}

    /** Clears {@code inventory} and writes every slot of {@code gui} into it. */
    public static void fill(Inventory inventory, RuntimeGuis.Live gui) {
        inventory.clear();
        for (RuntimeGuis.Slot slot : gui.slots()) {
            inventory.setItem(slot.slot(), stackOf(slot));
        }
    }

    private static ItemStack stackOf(RuntimeGuis.Slot slot) {
        ItemStack stack = switch (slot.item()) {
            case RuntimeGuis.Item.Materione materione ->
                    MaterioneStacks.build(MaterioneStacks.specOf(materione.item()));
            case RuntimeGuis.Item.Vanilla vanilla -> new ItemStack(vanilla.material());
        };
        stack.setAmount(Math.max(1, Math.min(slot.amount(), stack.getMaxStackSize())));
        return stack;
    }
}
