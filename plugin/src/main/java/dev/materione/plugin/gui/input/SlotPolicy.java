package dev.materione.plugin.gui.input;

import org.bukkit.event.inventory.InventoryAction;

/**
 * What one click the server applies itself does to one input slot (logic.md §25). A click on an input
 * slot is checked before vanilla applies it: {@code gui.slot_change} and the slot's {@code accept:} see
 * the change this table predicts, and a refusal cancels the click, so the change never happens. The
 * table is pure; {@link InputSlotClicks} reads the event into it.
 *
 * <p>The amounts are the server's own rules for each action (a half pickup takes the rounded-up half,
 * a place fills what the stack has room for). A prediction that is off by an amount changes what a
 * rule sees, never what the server does: the server applies the click itself, and it conserves items.
 *
 * <p>Actions that are not one input slot's own change (a drop key, the creative clone, an unknown
 * action) have no change, so {@link #change} returns null and the click is cancelled.
 */
public final class SlotPolicy {

    /** Bukkit's hotbar button for the offhand swap (the F key). */
    public static final int OFFHAND_BUTTON = 40;

    private SlotPolicy() {}

    /**
     * One predicted change to an input slot.
     *
     * @param entering the item that enters the slot, for {@code accept:} and {@code gui.slot_change};
     *     null when nothing enters (a pure removal)
     * @param afterAmount how many the slot holds after the click
     * @param afterId the identity the slot holds after the click; blank when it is empty
     */
    public record Change(StackView entering, int afterAmount, String afterId) {

        public Change {
            afterId = afterAmount <= 0 ? "" : afterId == null ? "" : afterId;
        }
    }

    /**
     * The predicted change of one click on an input slot.
     *
     * @param action what the click does, as Bukkit reports it
     * @param hotbarButton 0 to 8 for a number key, {@link #OFFHAND_BUTTON} for the offhand swap, -1 otherwise
     * @param slot the slot before the click
     * @param cursor the item on the cursor before the click
     * @param swapWith the hotbar item the number key names, or the offhand item; empty when there is none
     * @return the change, or null when the action is not allowed on an input slot
     */
    public static Change change(
            InventoryAction action, int hotbarButton, StackView slot, StackView cursor, StackView swapWith) {
        return switch (action) {
            case PICKUP_ALL -> new Change(null, 0, "");
            case PICKUP_HALF -> {
                int left = slot.amount() / 2;
                yield new Change(null, left, slot.id());
            }
            case PICKUP_ONE -> new Change(null, slot.amount() - 1, slot.id());
            case PICKUP_SOME -> {
                int room = cursor.empty() ? slot.maxStack() : cursor.maxStack() - cursor.amount();
                int taken = Math.max(0, Math.min(slot.amount(), room));
                yield new Change(null, slot.amount() - taken, slot.id());
            }
            case PLACE_ALL, PLACE_SOME -> place(slot, cursor, cursor.amount());
            case PLACE_ONE -> place(slot, cursor, 1);
            case SWAP_WITH_CURSOR -> cursor.empty()
                    ? new Change(null, 0, "")
                    : new Change(cursor, cursor.amount(), cursor.id());
            case HOTBAR_SWAP -> swapWith.empty()
                    ? new Change(null, 0, "")
                    : new Change(swapWith, swapWith.amount(), swapWith.id());
            default -> null;
        };
    }

    /** A placement of up to {@code wanted} items from the cursor into the slot, as far as the stack has room. */
    private static Change place(StackView slot, StackView cursor, int wanted) {
        boolean sameOrEmpty = slot.empty() || slot.id().equals(cursor.id());
        if (!sameOrEmpty || cursor.empty()) {
            return new Change(null, slot.amount(), slot.id());
        }
        int room = slot.empty() ? cursor.maxStack() : cursor.maxStack() - slot.amount();
        int added = Math.max(0, Math.min(wanted, Math.min(room, cursor.amount())));
        if (added == 0) {
            return new Change(null, slot.amount(), slot.id());
        }
        return new Change(cursor.withAmount(added), slot.amount() + added, cursor.id());
    }
}
