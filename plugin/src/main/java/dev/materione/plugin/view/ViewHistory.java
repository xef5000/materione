package dev.materione.plugin.view;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * One player's view history (logic.md §6.1, views.md §3.6): the views they left by opening another, most recent
 * first, of any opened kind (a GUI, then a dialog, then back to the GUI). {@code view.back} pops it. It is
 * bounded, and the oldest entry falls off when it is full, so a view that opens itself in a loop cannot grow it
 * without limit. A view that closes (rather than being replaced) clears it: "back" is navigation between views,
 * not a memory of every view ever seen.
 *
 * <p>Used on the player's own thread only; the methods are synchronized for the one cross-thread reader, the quit
 * path.
 */
public final class ViewHistory {

    /** How many views deep "back" goes. */
    public static final int MAX_DEPTH = 16;

    private final Deque<ViewerRecord.Snapshot> stack = new ArrayDeque<>();

    /** Remembers a record that was just replaced. */
    public synchronized void push(ViewerRecord.Snapshot snapshot) {
        stack.addFirst(snapshot);
        while (stack.size() > MAX_DEPTH) {
            stack.removeLast();
        }
    }

    /** Removes and returns the most recent entry. */
    public synchronized Optional<ViewerRecord.Snapshot> pop() {
        return Optional.ofNullable(stack.pollFirst());
    }

    /** The most recent entry, left in place. */
    public synchronized Optional<ViewerRecord.Snapshot> peek() {
        return Optional.ofNullable(stack.peekFirst());
    }

    public synchronized int size() {
        return stack.size();
    }

    public synchronized void clear() {
        stack.clear();
    }
}
