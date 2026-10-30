package dev.materione.plugin.gui.session;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * One player's back stack (logic.md §6.1): the GUIs they left by opening another, most recent first.
 * {@code gui.back} pops it. It is bounded, and the oldest entry falls off when it is full, so a menu
 * that opens itself in a loop cannot grow it without limit. A window that closes (rather than being
 * replaced) clears it: "back" is navigation between menus, not a memory of every menu ever seen.
 *
 * <p>Used on the player's own thread only; the methods are synchronized for the one cross-thread
 * reader, the quit path.
 */
public final class SessionHistory {

    /** How many menus deep "back" goes. */
    public static final int MAX_DEPTH = 16;

    private final Deque<GuiSession.Snapshot> stack = new ArrayDeque<>();

    /** Remembers a session that was just replaced. */
    public synchronized void push(GuiSession.Snapshot snapshot) {
        stack.addFirst(snapshot);
        while (stack.size() > MAX_DEPTH) {
            stack.removeLast();
        }
    }

    /** Removes and returns the most recent entry. */
    public synchronized Optional<GuiSession.Snapshot> pop() {
        return Optional.ofNullable(stack.pollFirst());
    }

    /** The most recent entry, left in place. */
    public synchronized Optional<GuiSession.Snapshot> peek() {
        return Optional.ofNullable(stack.peekFirst());
    }

    public synchronized int size() {
        return stack.size();
    }

    public synchronized void clear() {
        stack.clear();
    }
}
