package dev.materione.plugin.gui.input;

/**
 * The part of one inventory stack the input-slot rules read, with no Bukkit type in it, so the policy
 * and the transfer plans are unit-tested without a server ({@link BukkitStacks} fills it from a real
 * stack).
 *
 * @param id the item's identity as an author writes it: a Materione id, or {@code minecraft:<material>};
 *     blank for an empty stack
 * @param amount how many; 0 for an empty stack
 * @param maxStack the largest stack the item takes
 */
public record StackView(String id, int amount, int maxStack) {

    /** An empty slot. */
    public static final StackView EMPTY = new StackView("", 0, 64);

    public StackView {
        id = id == null ? "" : id;
    }

    public boolean empty() {
        return amount <= 0;
    }

    /** The same stack with another amount. */
    public StackView withAmount(int count) {
        return new StackView(id, count, maxStack);
    }
}
