package dev.materione.plugin.escrow.runtime;

import dev.materione.plugin.escrow.model.StackBytes;

/**
 * What the tier-1 runtime needs to know about one item stack, so the click rules and the transfer steps are
 * generic over the stack type and tested without a server (as {@code InputJournal<K, T>} is). The Bukkit
 * implementation is {@link ItemStacks}; a test uses a plain value.
 *
 * <p>An <em>empty</em> stack is {@code null}, or any value this implementation reports empty (air, an amount of 0).
 *
 * @param <T> one stack
 */
public interface Stacks<T> {

    /** Whether the stack is nothing (null, air, or an amount of zero or less). */
    boolean empty(T stack);

    /** How many items the stack holds; 0 when empty. */
    int amount(T stack);

    /** The largest stack of this stack's item. */
    int maxStack(T stack);

    /** Whether two non-empty stacks may merge: the same item and the same data. */
    boolean similar(T a, T b);

    /** A copy of {@code stack} holding {@code amount} items; {@code null} when the amount is zero or less. */
    T withAmount(T stack, int amount);

    /**
     * The stack as the platform serialises it (F13).
     *
     * @throws RuntimeException when the stack cannot be serialised: the transfer is then refused (I7)
     */
    StackBytes encode(T stack);

    /**
     * The stack of these bytes.
     *
     * @throws RuntimeException when the bytes are not a stack this server reads
     */
    T decode(StackBytes bytes);
}
