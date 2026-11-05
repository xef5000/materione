package dev.materione.plugin.gui.list;

import dev.materione.content.gui.list.ListSearch;

import dev.materione.behavior.flow.Condition;
import dev.materione.content.gui.CompiledGui;
import dev.materione.content.gui.list.ListSort;
import dev.materione.plugin.gui.dynamic.CompiledItemLayer;
import java.util.List;

/**
 * One {@code list} layer compiled for the live view (logic.md §6.7): its slots, its source, its filter and sort, and
 * the item template every entry is shown with. Built by {@link ListLayerCompiler}; read by the slot renderer on every
 * render. Immutable and compared on a reload through {@link #authored}.
 *
 * @param layerId the layer's id
 * @param order the layer's index among the GUI's layers: a higher order is a higher layer
 * @param slots the slots the entries fill, in entry order
 * @param source the source binding
 * @param cached whether the source is read when the window opens and on {@code gui.refresh} only
 * @param filter the compiled filter over {@code entry.*}, or null when every entry shows
 * @param sort the order of the entries, or null for the source's own
 * @param search which fields a session search keeps, or null when the list is not searchable (logic.md §28.21)
 * @param entry the item template, with its slot a placeholder
 * @param authored the layer as authored, compared by a reload (the binding itself has identity equality)
 */
public record CompiledListLayer(
        String layerId, int order, List<Integer> slots, ListBinding source, boolean cached, Condition filter,
        ListSort sort, ListSearch search, CompiledItemLayer entry, CompiledGui.ListPlacement authored) {

    public CompiledListLayer {
        slots = List.copyOf(slots);
    }

    /** A list that takes no search (the shape before #220). */
    public CompiledListLayer(
            String layerId, int order, List<Integer> slots, ListBinding source, boolean cached, Condition filter,
            ListSort sort, CompiledItemLayer entry, CompiledGui.ListPlacement authored) {
        this(layerId, order, slots, source, cached, filter, sort, null, entry, authored);
    }
}
