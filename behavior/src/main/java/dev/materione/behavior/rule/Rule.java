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
 * @param filters the keys the rule set on its trigger ({@code layer}, {@code click}, {@code every}),
 *        as the trigger's description declares them; empty for a trigger with none
 * @param path where the rule was written, e.g. {@code behavior.2}
 * @param onCooldown the flow that runs when the rule matched but its cooldown blocked it (logic.md §16); empty when
 *        the rule has none, and then a blocked rule stays silent
 */
public record Rule(
        NamespacedId owner,
        String id,
        EventSelector on,
        Condition condition,
        List<Step> steps,
        RuleOptions options,
        RuleFilters filters,
        StepPath path,
        List<Step> onCooldown) {

    public Rule {
        steps = List.copyOf(steps);
        filters = filters == null ? RuleFilters.none() : filters;
        onCooldown = onCooldown == null ? List.of() : List.copyOf(onCooldown);
    }

    /** A rule with no {@code on_cooldown} flow. */
    public Rule(
            NamespacedId owner,
            String id,
            EventSelector on,
            Condition condition,
            List<Step> steps,
            RuleOptions options,
            RuleFilters filters,
            StepPath path) {
        this(owner, id, on, condition, steps, options, filters, path, List.of());
    }

    /**
     * The run an {@code on_cooldown} flow starts as: the same identity and path, with the cooldown branch as its body.
     * The engine starts it with a context that holds {@code cooldown.remaining}.
     */
    public Rule cooldownRule() {
        return new Rule(owner, id, on, condition, onCooldown, options, filters, path);
    }

    /** The rule's stable identity ({@code <owner>#<name>}) for logs and cooldown keys. */
    public Optional<String> namedId() {
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
    }
}
