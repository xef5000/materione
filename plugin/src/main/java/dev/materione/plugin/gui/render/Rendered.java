package dev.materione.plugin.gui.render;

import dev.materione.plugin.gui.list.ListPage;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One render of a window (logic.md §6.3): what each slot shows, which layers are hidden by their
 * {@code visible_if} (the click router reads this, so it routes to what the viewer sees), what each item layer
 * resolved to, so a refresh that re-reads only the dynamic layers keeps the rest, and what each list shows
 * (logic.md §6.7).
 *
 * @param slots the appearance of every filled slot; an absent slot is empty
 * @param hidden the ids of the layers whose condition was false
 * @param resolved the resolved appearance of each layer that was resolved; empty when it resolved to nothing
 * @param title the window's title as rendered, or null before the first render
 * @param coverage the slots each raster or text layer covers in the title, by layer
 * @param lists what each list layer showed: the entries by slot, the source snapshots and the page state
 */
public record Rendered(Map<Integer, Appearance> slots, Set<String> hidden, Map<String, Optional<Appearance>> resolved,
        String title, Map<String, Set<Integer>> coverage, ListRender lists) {

    /** The state of a window that is not rendered yet. */
    public static final Rendered EMPTY = new Rendered(Map.of(), Set.of(), Map.of(), null, Map.of(), ListRender.EMPTY);

    public Rendered(Map<Integer, Appearance> slots, Set<String> hidden, Map<String, Optional<Appearance>> resolved) {
        this(slots, hidden, resolved, null, Map.of(), ListRender.EMPTY);
    }

    public Rendered(Map<Integer, Appearance> slots, Set<String> hidden, Map<String, Optional<Appearance>> resolved,
            String title, Map<String, Set<Integer>> coverage) {
        this(slots, hidden, resolved, title, coverage, ListRender.EMPTY);
    }

    public Rendered {
        slots = Map.copyOf(slots);
        hidden = Set.copyOf(hidden);
        resolved = Map.copyOf(resolved);
        var copy = new HashMap<String, Set<Integer>>();
        coverage.forEach((key, value) -> copy.put(key, Set.copyOf(value)));
        coverage = Map.copyOf(copy);
        lists = lists == null ? ListRender.EMPTY : lists;
    }

    /**
     * What the lists of a window showed in one render.
     *
     * @param shown the entry each list slot shows, by slot; a slot with no entry is absent
     * @param entries the entries each list read from its source, by list id (the snapshot a cached list keeps)
     * @param pages the page each list showed, by list id
     */
    public record ListRender(
            Map<Integer, Shown> shown, Map<String, List<Map<String, Object>>> entries, Map<String, ListPage> pages) {

        /** The lists of a window that has none, or has not rendered. */
        public static final ListRender EMPTY = new ListRender(Map.of(), Map.of(), Map.of());

        public ListRender {
            shown = Map.copyOf(shown);
            var snapshots = new HashMap<String, List<Map<String, Object>>>();
            entries.forEach((layer, rows) -> snapshots.put(layer, List.copyOf(rows)));
            entries = Map.copyOf(snapshots);
            pages = Map.copyOf(pages);
        }

        /** The entry a list slot shows in this render, when it shows one. */
        public Optional<Shown> shownAt(int slot) {
            return Optional.ofNullable(shown.get(slot));
        }

        /**
         * One list slot's entry.
         *
         * @param layer the list layer that shows it
         * @param entry the entry's fields, as {@code entry.*}
         */
        public record Shown(String layer, Map<String, Object> entry) {

            public Shown {
                entry = Collections.unmodifiableMap(new LinkedHashMap<>(entry)); // an entry field may be null
            }
        }
    }
}
