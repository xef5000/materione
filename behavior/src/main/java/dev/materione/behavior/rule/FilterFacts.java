package dev.materione.behavior.rule;

import java.util.Map;

/**
 * What an event says about itself for the rules' filters (see {@link RuleFilters}): for a
 * {@code gui.click} the layer that was hit, the slot and the click kind. The firing site builds it;
 * the engine only compares. Keys are the filter ids the trigger declares.
 */
@FunctionalInterface
public interface FilterFacts {

    /** No facts: only rules without filters match. */
    FilterFacts NONE = key -> null;

    /** The event's value for {@code key}, or null when it has none. */
    String get(String key);

    /** Facts from a map (a missing key is no fact). */
    static FilterFacts of(Map<String, String> facts) {
        return facts::get;
    }
}
