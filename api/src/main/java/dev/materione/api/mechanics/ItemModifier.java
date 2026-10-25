package dev.materione.api.mechanics;

import org.bukkit.inventory.ItemStack;

/**
 * An idempotent stack-construction hook, also reapplied on refresh. Preserve
 * Materione identity, material, amount and player-owned state (enchantments,
 * counters, contents). The host applies edits to a clone and commits metadata
 * only after successful completion, so a failed hook cannot corrupt a stack.
 * Runs on the caller's owning thread; never perform IO or schedule work here.
 * Version-sensitive component edits belong behind the platform boundary.
 */
@FunctionalInterface
public interface ItemModifier {
    void apply(ItemStack stack);
}
