package dev.materione.content.gui.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An animated item: the layer shows {@code items[(elapsed / every) mod n]}, where {@code elapsed} is the
 * window's clock in ticks (logic.md §6.3). Parsed at load; the clock is the GUI's, and it runs at the
 * greatest common divisor of every frame rate and the GUI's {@code refresh:}.
 *
 * @param every ticks between two frames, at least 1
 * @param items the sources the frames cycle through, in order, at least one
 */
public record ItemFrames(int every, List<ItemSpec> items) {

    private static final Set<String> KEYS = Set.of("every", "items");

    public ItemFrames {
        Objects.requireNonNull(items, "items");
        items = List.copyOf(items);
        if (every < 1) {
            throw new IllegalArgumentException("frames.every is a whole number of ticks, at least 1");
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("frames.items needs at least one item");
        }
    }

    /**
     * Parses a {@code frames:} mapping, {@code { every: <ticks>, items: [ ... ] }} and nothing else.
     *
     * @throws IllegalArgumentException with the reason, when the mapping is not that shape
     */
    public static ItemFrames parse(Map<?, ?> mapping) {
        for (Object key : mapping.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("frames takes every and items, not '" + key + "'");
            }
        }
        if (!(mapping.get("every") instanceof Number every) || !wholeNumber(every) || every.intValue() < 1) {
            throw new IllegalArgumentException("frames.every is a whole number of ticks, at least 1");
        }
        if (!(mapping.get("items") instanceof List<?> entries)) {
            throw new IllegalArgumentException("frames.items is a list of item sources");
        }
        var sources = new ArrayList<ItemSpec>(entries.size());
        for (Object entry : entries) {
            sources.add(ItemSpec.parse(entry));
        }
        return new ItemFrames(every.intValue(), sources);
    }

    /** Frames are always dynamic: their item changes with the clock. */
    public boolean dynamic() {
        return true;
    }

    private static boolean wholeNumber(Number value) {
        double decimal = value.doubleValue();
        return Double.isFinite(decimal) && decimal == Math.rint(decimal) && decimal <= Integer.MAX_VALUE;
    }
}
