package dev.materione.plugin.view;

import dev.materione.api.NamespacedId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * How often each view definition was re-rendered by a write, for {@code /materione doctor} (views.md section 5.7): the
 * definitions with the most automatic renders per second, so an owner can see which variable drives a cost. Counting
 * is one map lookup and one add on the owner's thread. A window of at most five minutes is kept, then restarted.
 */
public final class ViewRenderStats implements ViewScheduler.RenderCounter {

    private static final long WINDOW_MILLIS = 300_000;

    /** One definition's count and rate over the window. */
    public record Row(ViewRef view, long renders, double perSecond) {}

    private final LongSupplier millis;
    private final ConcurrentHashMap<ViewRef, LongAdder> counts = new ConcurrentHashMap<>();
    private volatile long since;

    /** @param millis the clock, in milliseconds (a virtual clock in tests) */
    public ViewRenderStats(LongSupplier millis) {
        this.millis = millis;
        this.since = millis.getAsLong();
    }

    @Override
    public void rendered(String kind, NamespacedId definition) {
        long now = millis.getAsLong();
        if (now - since > WINDOW_MILLIS) {
            counts.clear();
            since = now;
        }
        counts.computeIfAbsent(new ViewRef(kind, definition), ignored -> new LongAdder()).increment();
    }

    /** The {@code limit} definitions with the highest rate, highest first. */
    public List<Row> top(int limit) {
        double seconds = Math.max(1, millis.getAsLong() - since) / 1000.0;
        List<Row> rows = new ArrayList<>();
        counts.forEach((view, count) -> rows.add(new Row(view, count.sum(), count.sum() / seconds)));
        rows.sort(Comparator.comparingDouble(Row::perSecond).reversed());
        return rows.size() <= limit ? rows : rows.subList(0, limit);
    }

    /** The doctor's lines: one per definition, empty when nothing re-rendered. */
    public List<String> lines(int limit) {
        return top(limit).stream()
                .map(row -> String.format(Locale.ROOT, "%s %s: %.2f re-renders/s (%d)",
                        row.view().kind(), row.view().definition(), row.perSecond(), row.renders()))
                .toList();
    }
}
