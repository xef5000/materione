package dev.materione.api.logic;

import org.bukkit.inventory.ItemStack;

/**
 * What an {@link ItemSource} produced for one viewer (logic.md §4.4): an item, nothing at all (an
 * empty hand, a slot with no item), or a reason it could not be resolved. The reason is shown to the
 * viewer as the lore of a barrier item, so an unresolvable source is visible, never air.
 *
 * @param stack the item, or null for "nothing here"
 * @param reason why the item could not be resolved, or null when it was (or there is nothing to resolve)
 */
public record ItemResolution(ItemStack stack, String reason) {

    /** Nothing to show: the slot stays empty. */
    public static final ItemResolution NONE = new ItemResolution(null, null);

    /** The item the source produced; the caller owns it and must not assume it is a fresh copy. */
    public static ItemResolution ok(ItemStack stack) {
        return stack == null ? NONE : new ItemResolution(stack, null);
    }

    /** Nothing to show. */
    public static ItemResolution none() {
        return NONE;
    }

    /** The source could not produce an item; the reason is shown on a barrier. */
    public static ItemResolution unresolved(String reason) {
        return new ItemResolution(null, reason == null || reason.isBlank() ? "unknown reason" : reason);
    }

    /** True when the source failed; a {@link #NONE} result is not a failure. */
    public boolean failed() {
        return reason != null;
    }
}
