package dev.materione.behavior.rule;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import java.util.List;

/** The outcome of parsing one resource's {@code behavior:} section. */
public record ParsedRules(List<Rule> rules, List<Diagnostic> problems) {

    public static ParsedRules empty() {
        return new ParsedRules(List.of(), List.of());
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }
}
