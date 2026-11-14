package dev.materione.plugin.runtime.behavior;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.UnfiredTriggers;
import dev.materione.content.rules.RuleDefinition;
import dev.materione.content.rules.RuleType;
import java.util.List;
import java.util.function.Consumer;

/**
 * Compiles one rule document for the shadow (logic.md §37): the same flow compiler an item's rules go
 * through, so a rule document reports exactly the problems an item would (an unknown action, a bad filter,
 * a path the trigger does not provide), each with its step path. One bad rule is skipped with {@code MTR-0021}
 * and its siblings load; the document is never lost for it.
 *
 * <p>It adds the one check only a host can make: a rule whose trigger no rule document fires (a
 * {@code click}, which an item fires) loads and never runs, which an author cannot see, so it is a warning
 * with the same code a GUI uses for the same mistake.
 */
public final class RuleDocuments {

    private RuleDocuments() {}

    /**
     * @return the document's loaded rules, empty when it has none or none loaded; problems go to {@code report}
     */
    public static List<Rule> compile(RuleDefinition document, LogicLookup lookup, Consumer<Diagnostic> report) {
        ParsedRules parsed = FlowCompiler.compile(document.id(), document.behavior(), document.flows(), lookup);
        parsed.problems().forEach(report);
        // the same check the offline validation makes (UnfiredTriggers), where the host is known
        UnfiredTriggers.check(parsed.rules(), lookup, RuleType.NAME, document.id()).forEach(report);
        return parsed.rules();
    }

}
