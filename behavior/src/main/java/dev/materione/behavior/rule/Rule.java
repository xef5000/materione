package dev.materione.behavior.rule;

import dev.materione.api.NamespacedId;
import dev.materione.behavior.expr.ExprNode;
import java.util.List;
import java.util.Optional;

/**
 * One loaded rule: {@code Rule = {id, on: EventSelector, if: [conditions],
 * do: [actions], opts}} (runtime-systems §2.1). Immutable and swapped
 * atomically with the shadow — an in-flight event always completes against
 * the snapshot it started with.
 */
public record Rule(
        NamespacedId owner,
        String id,
        EventSelector on,
        List<ExprNode> conditions,
        List<ActionSpec> actions,
        RuleOptions options) {

    public Rule {
        conditions = List.copyOf(conditions);
        actions = List.copyOf(actions);
    }

    /** The rule's stable identity ({@code <owner>#<name>}) for logs and cooldown keys. */
    public Optional<String> namedId() {
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
    }
}
