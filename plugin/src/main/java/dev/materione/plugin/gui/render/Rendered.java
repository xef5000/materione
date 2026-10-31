package dev.materione.plugin.gui.render;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One render of a window (logic.md §6.3): what each slot shows, which layers are hidden by their
 * {@code visible_if} (the click router reads this, so it routes to what the viewer sees), and what each
 * item layer resolved to, so a refresh that re-reads only the dynamic layers keeps the rest.
 *
 * @param slots the appearance of every filled slot; an absent slot is empty
 * @param hidden the ids of the layers whose condition was false
 * @param resolved the resolved appearance of each layer that was resolved; empty when it resolved to nothing
 */
public record Rendered(Map<Integer, Appearance> slots, Set<String> hidden, Map<String, Optional<Appearance>> resolved) {

    /** The state of a window that is not rendered yet. */
    public static final Rendered EMPTY = new Rendered(Map.of(), Set.of(), Map.of());

    public Rendered {
        slots = Map.copyOf(slots);
        hidden = Set.copyOf(hidden);
        resolved = Map.copyOf(resolved);
    }
}
