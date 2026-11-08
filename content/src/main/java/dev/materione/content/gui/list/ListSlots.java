package dev.materione.content.gui.list;

import dev.materione.api.gui.GuiContainer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The container slots a list fills, as authored (logic.md §6.7). Two forms: a range with columns skipped
 * ({@code slots: {from: 10, to: 34, skip_columns: [0, 8]}}), or an explicit list of slots. The order is the
 * order of the entries: the first entry shows in the first slot. Columns are the container's grid (gui.md §12.5.2).
 */
public final class ListSlots {

    private ListSlots() {}

    /**
     * Parses the authored {@code slots:} of a list in a GUI of {@code container}.
     *
     * @return the slots in entry order; never empty and never repeated
     * @throws IllegalArgumentException with the reason
     */
    public static List<Integer> parse(Object raw, GuiContainer container) {
        int size = container.slotCount();
        int columns = container.columns();
        List<Integer> slots;
        if (raw instanceof Map<?, ?> range) {
            slots = range(range, size, columns);
        } else if (raw instanceof List<?> explicit) {
            slots = explicit(explicit, size);
        } else {
            throw new IllegalArgumentException(
                    "slots is a range ({from, to, skip_columns}) or a list of slot numbers");
        }
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("slots names no slot of the GUI");
        }
        return List.copyOf(slots);
    }

    private static List<Integer> range(Map<?, ?> range, int size, int columns) {
        for (Object key : range.keySet()) {
            if (!"from".equals(key) && !"to".equals(key) && !"skip_columns".equals(key)) {
                throw new IllegalArgumentException("slots takes from, to and skip_columns, not '" + key + "'");
            }
        }
        int from = slotNumber(range.get("from"), "from", size);
        int to = slotNumber(range.get("to"), "to", size);
        if (to < from) {
            throw new IllegalArgumentException("slots to must be at or after from");
        }
        Set<Integer> skipped = new HashSet<>();
        if (range.get("skip_columns") != null) {
            if (!(range.get("skip_columns") instanceof List<?> listed)) {
                throw new IllegalArgumentException("skip_columns is a list of column numbers from 0 to " + (columns - 1));
            }
            for (Object column : listed) {
                Integer number = wholeNumber(column);
                if (number == null || number < 0 || number >= columns) {
                    throw new IllegalArgumentException(
                            "skip_columns holds column numbers from 0 to " + (columns - 1));
                }
                skipped.add(number);
            }
        }
        var out = new ArrayList<Integer>();
        for (int slot = from; slot <= to; slot++) {
            if (!skipped.contains(slot % columns)) {
                out.add(slot);
            }
        }
        return out;
    }

    private static List<Integer> explicit(List<?> explicit, int size) {
        var out = new ArrayList<Integer>(explicit.size());
        var seen = new HashSet<Integer>();
        for (Object entry : explicit) {
            int slot = slotNumber(entry, "a slot", size);
            if (!seen.add(slot)) {
                throw new IllegalArgumentException("slot " + slot + " is listed twice");
            }
            out.add(slot);
        }
        return out;
    }

    private static int slotNumber(Object value, String name, int size) {
        Integer number = wholeNumber(value);
        if (number == null || number < 0 || number >= size) {
            throw new IllegalArgumentException(name + " must be a slot from 0 to " + (size - 1)
                    + " of this GUI");
        }
        return number;
    }

    /** A whole number as authored (YAML may read it as any numeric type), or null. */
    private static Integer wholeNumber(Object value) {
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (Double.isFinite(decimal) && decimal == Math.rint(decimal)
                    && decimal >= Integer.MIN_VALUE && decimal <= Integer.MAX_VALUE) {
                return (int) decimal;
            }
        }
        return null;
    }
}
