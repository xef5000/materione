package dev.materione.api.logic.trace;

import java.util.List;

/**
 * What one delivery to a {@link TraceListener} carries: the entries since the last delivery, in the order the
 * engine produced them, and how many were dropped on the way (the rate limit, a run past its step cap, a full
 * queue). A drop is a count, never an exception into the engine (logic.md §32).
 *
 * @param entries the entries, oldest first; possibly empty when only a drop is being reported
 * @param dropped how many entries this subscription lost since the last delivery
 */
public record TraceBatch(List<TraceEntry> entries, int dropped) {

    public TraceBatch {
        entries = List.copyOf(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty() && dropped == 0;
    }
}
