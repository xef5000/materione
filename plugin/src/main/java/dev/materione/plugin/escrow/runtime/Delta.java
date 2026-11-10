package dev.materione.plugin.escrow.runtime;

import java.util.Map;
import java.util.TreeMap;

/**
 * What one manual click does to the live state, as the plain stacks it leaves behind: the window's slots, the player's
 * inventory slots and the cursor (gui-input-durability.md §5.5: the plugin cancels the event and applies the whole
 * result itself). A planner builds it; {@link Tier1#apply} turns it into one owner-thread step together with the
 * account change of every escrowed slot it touches.
 *
 * @param <T> one stack
 */
public final class Delta<T> {

    private final Map<Integer, T> top = new TreeMap<>();
    private final Map<Integer, T> bag = new TreeMap<>();
    private T cursor;
    private boolean cursorSet;

    /** The window slot {@code slot} ends holding {@code stack} (null: empty). */
    public Delta<T> top(int slot, T stack) {
        top.put(slot, stack);
        return this;
    }

    /** The player's inventory slot {@code index} ends holding {@code stack} (null: empty). */
    public Delta<T> bag(int index, T stack) {
        bag.put(index, stack);
        return this;
    }

    /** The cursor ends holding {@code stack} (null: empty). */
    public Delta<T> cursor(T stack) {
        this.cursor = stack;
        this.cursorSet = true;
        return this;
    }

    /** The window slots written, in slot order. */
    public Map<Integer, T> tops() {
        return top;
    }

    /** The inventory slots written, in index order. */
    public Map<Integer, T> bags() {
        return bag;
    }

    public boolean cursorSet() {
        return cursorSet;
    }

    public T cursorStack() {
        return cursor;
    }

    public boolean isEmpty() {
        return top.isEmpty() && bag.isEmpty() && !cursorSet;
    }
}
