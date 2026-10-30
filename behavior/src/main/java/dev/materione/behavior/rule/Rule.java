package dev.materione.behavior.rule;

import dev.materione.api.NamespacedId;
import dev.materione.behavior.flow.Condition;
import dev.materione.behavior.flow.Step;
import dev.materione.behavior.flow.StepPath;
import java.util.List;
import java.util.Optional;

/**
 * One loaded rule: {@code Rule = {id, on: EventSelector, if: Condition, do: [Step], opts}}
 * (runtime-systems §2.1, logic.md §2.1). The body is a flow: an ordered tree of {@link Step}s whose
 * actions are already typed (parameters were parsed once, at load) and whose {@code call}s are
 * already linked to their flows. Immutable and swapped atomically with the shadow, so an in-flight
 * event always completes against the snapshot it started with.
 *
 * @param owner the resource that declares the rule
 * @param id the rule's stable name (authored {@code id:}, or {@code <owner>#<index>})
 * @param on which events it answers
 * @param condition the rule-level {@code if:}, evaluated before the flow starts (an {@code all} of
 *        the listed conditions; it holds when none are listed)
 * @param steps the flow
 * @param options cooldown, charges and their scope
 * @param path where the rule was written, e.g. {@code behavior.2}
 */
public record Rule(
        NamespacedId owner,
        String id,
        EventSelector on,
        Condition condition,
        List<Step> steps,
        RuleOptions options,
        StepPath path) {

    public Rule {
        steps = List.copyOf(steps);
    }

    /** The rule's stable identity ({@code <owner>#<name>}) for logs and cooldown keys. */
    public Optional<String> namedId() {
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
    }
}
