package dev.materione.content.rules;

import dev.materione.api.NamespacedId;
import java.util.List;
import java.util.Map;

/**
 * A parsed {@code rule} document (logic.md §37): rules and named flows that belong to no item, block,
 * furniture or GUI. {@code behavior} and {@code flows} are the authored values with their keys made
 * strings (a bare {@code on:} loads as a YAML 1.1 boolean key), ready for the flow compiler; {@code raw}
 * is the whole merged mapping, so a key this version does not know is kept.
 *
 * @param id the document's resource id ({@code welcome:greet})
 * @param description the author's note, or null
 * @param behavior the {@code behavior:} list as written; empty when the document has none
 * @param flows the {@code flows:} mapping as written, or null
 * @param raw the merged mapping
 */
public record RuleDefinition(
        NamespacedId id,
        String description,
        List<Object> behavior,
        Map<String, Object> flows,
        Map<String, Object> raw) {

    public RuleDefinition {
        behavior = behavior == null ? List.of() : List.copyOf(behavior);
        raw = Map.copyOf(raw);
    }
}
