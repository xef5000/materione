package dev.materione.content.gui.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which layers a click on each container slot reaches (logic.md §6.2), compiled with the GUI so a
 * click is one lookup and no geometry runs at play time. For every slot it holds the ids of the
 * layers whose footprint covers it, <b>topmost first</b> (the last layer in the file is first), so
 * the router walks the list and stops at the first visible layer with a rule for the click.
 *
 * <p>A hidden layer (or one the compiler skipped) is not in the map. A layer that is only
 * conditionally visible ({@code visible_if}) <em>is</em> in the map: whether it is visible is a
 * play-time question that the router asks per click.
 *
 * <p>Immutable. {@link #EMPTY} is the map of a GUI with nothing clickable.
 */
public final class ClickMap {

    /** A GUI with no clickable layer. */
    public static final ClickMap EMPTY = new ClickMap(List.of());

    private final List<List<String>> bySlot;

    ClickMap(List<List<String>> bySlot) {
        var copy = new ArrayList<List<String>>(bySlot.size());
        for (List<String> layers : bySlot) {
            copy.add(List.copyOf(layers));
        }
        this.bySlot = Collections.unmodifiableList(copy);
    }

    /** How many container slots the map was compiled for (0 for {@link #EMPTY}). */
    public int slotCount() {
        return bySlot.size();
    }

    /**
     * The layers a click on {@code slot} reaches, topmost first; empty for a slot nothing covers
     * and for a slot outside the container (the player's own inventory is not part of the map).
     */
    public List<String> layersAt(int slot) {
        return slot >= 0 && slot < bySlot.size() ? bySlot.get(slot) : List.of();
    }

    /** Whether {@code layerId} covers {@code slot}. */
    public boolean covers(String layerId, int slot) {
        return layersAt(slot).contains(layerId);
    }

    /** Every slot {@code layerId} covers, ascending; empty when the layer is not clickable. */
    public Set<Integer> slotsOf(String layerId) {
        var slots = new TreeSet<Integer>();
        for (int slot = 0; slot < bySlot.size(); slot++) {
            if (bySlot.get(slot).contains(layerId)) {
                slots.add(slot);
            }
        }
        return Collections.unmodifiableSet(slots);
    }

    /** The ids of every layer that covers at least one slot, in first-slot order. */
    public Set<String> layers() {
        var ids = new LinkedHashMap<String, Boolean>();
        bySlot.forEach(layers -> layers.forEach(id -> ids.put(id, Boolean.TRUE)));
        return Collections.unmodifiableSet(ids.keySet());
    }

    /** True when no slot is covered by any layer. */
    public boolean isEmpty() {
        return bySlot.stream().allMatch(List::isEmpty);
    }

    /** The per-slot lists as a map, for diagnostics and tests: only covered slots appear. */
    public Map<Integer, List<String>> asMap() {
        var out = new LinkedHashMap<Integer, List<String>>();
        for (int slot = 0; slot < bySlot.size(); slot++) {
            if (!bySlot.get(slot).isEmpty()) {
                out.put(slot, bySlot.get(slot));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ClickMap map && map.bySlot.equals(bySlot);
    }

    @Override
    public int hashCode() {
        return bySlot.hashCode();
    }

    @Override
    public String toString() {
        return "ClickMap" + asMap();
    }
}
