package dev.materione.plugin.gui.render;

import dev.materione.core.runtime.RuntimeItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * What a slot's item is built from (logic.md §6.3): a live Materione item, a vanilla material, a stack a
 * source produced for the viewer, or a barrier that names why nothing could be produced. Equal when the
 * appearance is the same, so the window writes a slot only when its source changed.
 */
public sealed interface SlotSource {

    /** A live Materione item, built through the item factory when it is written. */
    record Materione(RuntimeItem item) implements SlotSource {}

    /** A vanilla material, shown as a plain stack. */
    record Vanilla(Material material) implements SlotSource {}

    /** A stack an item source produced for this viewer; written as a copy. */
    record Stack(ItemStack stack) implements SlotSource {}

    /** An item source that could not produce its item: a barrier item whose lore is the reason. */
    record Barrier(String reason) implements SlotSource {}
}
