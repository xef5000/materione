package dev.materione.plugin.escrow.runtime;

import java.util.Optional;
import org.bukkit.event.inventory.InventoryAction;

/**
 * What one single-slot click does to an escrowed slot when the plugin applies it itself (gui-input-durability.md §5.5):
 * the stacks the slot, the cursor and (for a number key or the offhand swap) the hotbar or offhand slot end up
 * holding. The rules are vanilla's for each action Bukkit reports, so a manual click feels like the server's own: a
 * left pickup lifts the whole stack to the cursor, a right pickup the larger half, a place fills what the stack has room
 * for, a swap exchanges the two, and a hotbar key swaps the slot with that hotbar slot.
 *
 * <p>Pure and generic over the stack ({@link Stacks}): every action is tested without a server. An action that is not
 * one slot's own change (a drop key, the creative clone, an unknown action) has no plan, and a plan that contradicts
 * the state it is given (a pickup onto a full cursor) has none either: the click is then cancelled with no change.
 *
 * @param <T> one stack
 */
public final class ClickPlanner<T> {

    /** Bukkit's hotbar button for the offhand swap (the F key). */
    public static final int OFFHAND_BUTTON = 40;

    /**
     * The three stacks a click ends with.
     *
     * @param slot   what the window slot holds (null: empty)
     * @param cursor what the cursor holds (null: empty)
     * @param other  what the hotbar or offhand slot holds after a hotbar swap; unused otherwise
     */
    public record Outcome<T>(T slot, T cursor, T other) {}

    private final Stacks<T> stacks;

    public ClickPlanner(Stacks<T> stacks) {
        this.stacks = stacks;
    }

    /**
     * The result of one click on an input slot.
     *
     * @param action what the click does, as Bukkit reports it
     * @param slot   the slot before the click
     * @param cursor the cursor before the click
     * @param other  the hotbar or offhand stack the key names; ignored for every other action
     * @return the outcome, or empty when the action has no plan for this state
     */
    public Optional<Outcome<T>> plan(InventoryAction action, T slot, T cursor, T other) {
        T s = stacks.empty(slot) ? null : slot;
        T c = stacks.empty(cursor) ? null : cursor;
        T o = stacks.empty(other) ? null : other;
        return switch (action) {
            case PICKUP_ALL -> c != null || s == null ? Optional.empty() : outcome(null, s, o);
            case PICKUP_HALF -> c != null || s == null
                    ? Optional.empty()
                    : outcome(stacks.withAmount(s, stacks.amount(s) / 2),
                            stacks.withAmount(s, stacks.amount(s) - stacks.amount(s) / 2), o);
            case PICKUP_ONE -> c != null || s == null
                    ? Optional.empty()
                    : outcome(stacks.withAmount(s, stacks.amount(s) - 1), stacks.withAmount(s, 1), o);
            case PICKUP_SOME -> pickupSome(s, c, o);
            case PLACE_ALL, PLACE_SOME -> place(s, c, o, stacks.amount(c));
            case PLACE_ONE -> place(s, c, o, 1);
            case SWAP_WITH_CURSOR -> c == null ? Optional.empty() : outcome(c, s, o);
            case HOTBAR_SWAP -> s == null && o == null ? Optional.empty() : outcome(o, c, s);
            default -> Optional.empty();
        };
    }

    /** A pickup from a stack the slot would not take whole: up to what the cursor has room for. */
    private Optional<Outcome<T>> pickupSome(T s, T c, T o) {
        if (s == null || (c != null && !stacks.similar(s, c))) {
            return Optional.empty();
        }
        int room = c == null ? stacks.maxStack(s) : stacks.maxStack(c) - stacks.amount(c);
        int taken = Math.max(0, Math.min(stacks.amount(s), room));
        if (taken == 0) {
            return Optional.empty();
        }
        int cursorAmount = (c == null ? 0 : stacks.amount(c)) + taken;
        return outcome(stacks.withAmount(s, stacks.amount(s) - taken), stacks.withAmount(s, cursorAmount), o);
    }

    /** A placement of up to {@code wanted} items from the cursor, as far as the slot has room. */
    private Optional<Outcome<T>> place(T s, T c, T o, int wanted) {
        if (c == null || (s != null && !stacks.similar(s, c))) {
            return Optional.empty();
        }
        int max = Math.max(1, stacks.maxStack(c));
        int room = s == null ? max : max - stacks.amount(s);
        int added = Math.max(0, Math.min(wanted, Math.min(room, stacks.amount(c))));
        if (added == 0) {
            return Optional.empty();
        }
        int slotAmount = (s == null ? 0 : stacks.amount(s)) + added;
        return outcome(stacks.withAmount(s == null ? c : s, slotAmount),
                stacks.withAmount(c, stacks.amount(c) - added), o);
    }

    private Optional<Outcome<T>> outcome(T slot, T cursor, T other) {
        return Optional.of(new Outcome<>(slot, cursor, other));
    }
}
