package dev.materione.behavior.run.trace;

import dev.materione.api.logic.trace.TraceBatch;
import dev.materione.api.logic.trace.TraceEntry;
import dev.materione.api.logic.trace.TraceKind;
import dev.materione.api.logic.trace.TraceLimits;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One subscription's bounded buffer between the engine and its listener (logic.md §32): the rate limit, the
 * per-run step cap and the pending cap all live here, and all three end the same way, by counting a drop. It is
 * touched by whichever threads run flows and by the pump that delivers, so every method locks; the lock is held
 * for a handful of field updates and is only ever contended while someone is tracing.
 */
final class TraceQueue {

    /** The most runs whose step counts are remembered; the oldest is forgotten first. */
    private static final int RUNS_REMEMBERED = 32;

    private final TraceLimits limits;
    private final ArrayDeque<TraceEntry> pending = new ArrayDeque<>();
    private final Map<Long, Integer> stepsByRun = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
            return size() > RUNS_REMEMBERED;
        }
    };
    private double tokens;
    private long refilledAt;
    private int dropped;

    TraceQueue(TraceLimits limits, long now) {
        this.limits = limits;
        this.tokens = limits.eventsPerSecond();
        this.refilledAt = now;
    }

    /** Takes an entry in, or counts it as dropped. Never throws. */
    synchronized void offer(TraceEntry entry, long now) {
        boolean runLevel = entry.kind() == TraceKind.RUN_STARTED || entry.kind() == TraceKind.RUN_ENDED;
        if (!runLevel) {
            int steps = stepsByRun.merge(entry.runId(), 1, Integer::sum);
            if (steps > limits.maxEntriesPerRun()) {
                dropped++;
                return;
            }
        }
        refill(now);
        if (tokens < 1 || pending.size() >= limits.maxPending()) {
            dropped++;
            return;
        }
        tokens -= 1;
        pending.add(entry);
    }

    /** The next delivery, or null when there is nothing to say. */
    synchronized TraceBatch take() {
        if (pending.isEmpty() && dropped == 0) {
            return null;
        }
        var entries = new ArrayList<TraceEntry>(Math.min(pending.size(), limits.maxBatch()));
        while (!pending.isEmpty() && entries.size() < limits.maxBatch()) {
            entries.add(pending.poll());
        }
        int lost = dropped;
        dropped = 0;
        return new TraceBatch(entries, lost);
    }

    synchronized int pending() {
        return pending.size();
    }

    private void refill(long now) {
        long elapsed = now - refilledAt;
        if (elapsed > 0) {
            tokens = Math.min(limits.eventsPerSecond(), tokens + elapsed * limits.eventsPerSecond() / 1000.0);
            refilledAt = now;
        }
    }
}
