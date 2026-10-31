package dev.materione.plugin.logic.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The pure half of a take: which slots give how much, decided before anything is removed. A take
 * is all-or-nothing (logic.md §4.1), so {@link #plan} returns a plan only when the whole amount is
 * there; the caller applies it only then, and a short inventory changes nothing.
 */
public final class InventoryPlan {

    /** Remove {@code amount} from {@code slot}. */
    public record Take(int slot, int amount) {}

    private InventoryPlan() {}

    /** The predicate a matcher reads: the item, and the name text it must contain when one is given. */
    public static Predicate<ItemView> wanted(ItemRef ref, Optional<String> nameContains) {
        return ItemMatch.of(ref, nameContains).predicate();
    }

    /** How many matching items the slots hold in total. */
    public static int count(List<ItemView> slots, Predicate<ItemView> wanted) {
        int total = 0;
        for (ItemView view : slots) {
            if (view != null && wanted.test(view)) {
                total += view.amount();
            }
        }
        return total;
    }

    /**
     * The removals that take {@code amount} matching items, taken from the first slots that hold
     * them, or empty when the slots hold fewer. {@code slots.get(i)} is the stack in slot {@code i},
     * null when the slot is empty.
     */
    public static Optional<List<Take>> plan(List<ItemView> slots, Predicate<ItemView> wanted, int amount) {
        if (amount < 1 || count(slots, wanted) < amount) {
            return Optional.empty();
        }
        var takes = new ArrayList<Take>();
        int remaining = amount;
        for (int slot = 0; slot < slots.size() && remaining > 0; slot++) {
            ItemView view = slots.get(slot);
            if (view != null && wanted.test(view)) {
                int taken = Math.min(view.amount(), remaining);
                takes.add(new Take(slot, taken));
                remaining -= taken;
            }
        }
        return Optional.of(List.copyOf(takes));
    }
}
