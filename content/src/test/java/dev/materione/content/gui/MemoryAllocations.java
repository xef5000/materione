package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.AllocatedRange;
import dev.materione.api.content.Allocations;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory allocation view for the content tests: a counter per kind, bound per resource. It
 * stands in for the pack's table, whose preview view is the production counterpart.
 */
final class MemoryAllocations implements Allocations {

    private final Map<String, String> bound = new HashMap<>();
    private final Map<String, Integer> counters = new HashMap<>();

    @Override
    public String allocate(NamespacedId resource, String kind) {
        return bound.computeIfAbsent(resource + "|" + kind, key -> Integer.toString(
                counters.merge(kind, 1, Integer::sum)));
    }

    @Override
    public AllocatedRange range(NamespacedId resource, String kind, int count) {
        String key = resource + "|" + kind;
        String existing = bound.get(key);
        if (existing != null && AllocatedRange.parse(existing).count() >= count) {
            return AllocatedRange.parse(existing);
        }
        int last = counters.getOrDefault(kind, 0);
        var range = new AllocatedRange(last + 1, count);
        counters.put(kind, last + count);
        bound.put(key, range.toString());
        return range;
    }

    @Override
    public Optional<String> lookup(NamespacedId resource, String kind) {
        return Optional.ofNullable(bound.get(resource + "|" + kind));
    }
}
