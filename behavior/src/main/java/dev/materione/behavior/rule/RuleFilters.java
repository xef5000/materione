package dev.materione.behavior.rule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The extra keys a rule sets on its trigger beyond {@code on:} (logic.md §4.3, §6.2): a
 * {@code gui.click} rule says {@code layer: buy}, {@code click: [left, right]} or {@code slot: 13}, a
 * {@code gui.tick} rule says {@code every: 20}. The trigger's description declares which keys exist
 * (its {@code filters()}); the compiler reads exactly those keys off the rule, and the firing site
 * supplies the fact each one is compared with ({@link FilterFacts}).
 *
 * <p>A filter holds when the event's fact for that key is one of the rule's values. A rule with no
 * filter on a key accepts every fact for it; a rule that names a filter the event carries no fact
 * for does not match. Values are compared as text, case-insensitively, so {@code click: SHIFT_LEFT}
 * and {@code click: shift_left} are the same.
 *
 * @param values the filter values by key, in authored order; every list is non-empty
 */
public record RuleFilters(Map<String, List<String>> values) {

    private static final RuleFilters NONE = new RuleFilters(Map.of());

    public RuleFilters {
        var copy = new LinkedHashMap<String, List<String>>();
        values.forEach((key, list) -> copy.put(key, List.copyOf(list)));
        values = Collections.unmodifiableMap(copy);
    }

    /** A rule with no filters: it answers every event of its trigger. */
    public static RuleFilters none() {
        return NONE;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** The values the rule set for {@code key}, or empty when it did not filter on it. */
    public Optional<List<String>> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    /** Whether the rule filters on {@code key} at all. */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** The keys the rule filters on. */
    public Set<String> keys() {
        return values.keySet();
    }

    /**
     * Whether every filter of the rule holds for the facts of one event. An event with no facts
     * matches only a rule with no filters.
     */
    public boolean matches(FilterFacts facts) {
        for (var filter : values.entrySet()) {
            String fact = facts.get(filter.getKey());
            if (fact == null || !contains(filter.getValue(), fact)) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(List<String> allowed, String fact) {
        for (String value : allowed) {
            if (value.equalsIgnoreCase(fact)) {
                return true;
            }
        }
        return false;
    }
}
