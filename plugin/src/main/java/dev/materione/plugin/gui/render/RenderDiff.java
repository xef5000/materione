package dev.materione.plugin.gui.render;

import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Writes only the slots whose appearance changed between two renders (logic.md §6.3). Pure: the writer is a
 * port, so the decision is tested without a server and the Bukkit write happens only for the changed slots.
 */
public final class RenderDiff {

    /** Writes one slot; a null appearance clears it. */
    @FunctionalInterface
    public interface SlotWriter {
        void write(int slot, Appearance appearance);
    }

    private RenderDiff() {}

    /**
     * Calls {@code writer} for every slot that differs between {@code shown} and {@code next}, in slot order.
     *
     * @return how many slots were written
     */
    public static int apply(Map<Integer, Appearance> shown, Map<Integer, Appearance> next, SlotWriter writer) {
        var slots = new TreeSet<Integer>(shown.keySet());
        slots.addAll(next.keySet());
        int written = 0;
        for (int slot : slots) {
            Appearance before = shown.get(slot);
            Appearance after = next.get(slot);
            if (!Objects.equals(before, after)) {
                writer.write(slot, after);
                written++;
            }
        }
        return written;
    }
}
