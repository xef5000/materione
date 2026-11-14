package dev.materione.behavior.rule;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.behavior.logic.LogicLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The check only a behavior host can make: a rule whose trigger the host never fires loads and never runs, which
 * an author cannot see (logic.md section 37). One implementation, so the shadow build and the offline validation
 * (an authoring transaction's dry run) report the same warning with the same text.
 */
public final class UnfiredTriggers {

    private UnfiredTriggers() {}

    /**
     * @param host the host's name as {@code TriggerDescription.firedBy} spells it ({@code rule})
     * @return one {@code MTR-0021} warning per loaded rule whose trigger is fired by hosts that do not include
     *         {@code host}
     */
    public static List<Diagnostic> check(List<Rule> rules, LogicLookup lookup, String host, NamespacedId resourceId) {
        List<Diagnostic> out = new ArrayList<>();
        for (Rule rule : rules) {
            Optional<TriggerDescription> trigger = lookup.trigger(rule.on().type());
            if (trigger.isPresent() && trigger.get().fired() && !trigger.get().firedBy().contains(host)) {
                out.add(new Diagnostic(
                        DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN,
                        resourceId + " rule '" + rule.id() + "' at " + rule.path().child("on")
                                + ": trigger '" + rule.on().type() + "' is not fired for " + host
                                + " documents, so this rule never runs",
                        resourceId, null,
                        "use a trigger the editor lists for " + host + " documents, such as entity.kill or player.join",
                        rule.path().child("on").target()));
            }
        }
        return out;
    }
}
