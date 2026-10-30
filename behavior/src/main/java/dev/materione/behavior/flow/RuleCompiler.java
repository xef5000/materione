package dev.materione.behavior.flow;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.behavior.rule.EventSelector;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.RuleOptions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Compiles one rule (runtime-systems §2.1): the shell ({@code id}, {@code on}, {@code if},
 * {@code cooldown}, ...) and its {@code do:} flow. A rule that cannot be understood is skipped with
 * a diagnostic and its siblings load. The editor hint {@code node} and keys this version does not
 * know are ignored here and untouched in the file (lossless round-trip, master §5.6).
 */
final class RuleCompiler {

    private final CompileContext context;
    private final FlowSet flows;

    RuleCompiler(CompileContext context, FlowSet flows) {
        this.context = context;
        this.flows = flows;
    }

    /**
     * @param index the rule's position in {@code behavior:}
     * @return the rule, or empty after reporting why it was skipped
     */
    Optional<Rule> compile(int index, Object entry) {
        StepPath path = StepPath.of("behavior", Integer.toString(index));
        String fallbackId = context.owner + "#" + index;
        if (!(entry instanceof Map<?, ?> raw)) {
            report(fallbackId, path, "rule is not a mapping", "every rule needs on:/do: keys");
            return Optional.empty();
        }
        Map<String, Object> rule = Shapes.stringMap(raw);
        String id = rule.get("id") instanceof String named && !named.isBlank() ? named : fallbackId;
        String selectorText = rule.get("on") instanceof String text && !text.isBlank() ? text : null;
        if (selectorText == null) {
            report(id, path, "missing 'on:' event selector", "use an event from the taxonomy, e.g. on: click:right");
            return Optional.empty();
        }
        Optional<EventSelector> selector = context.lookup.selector(selectorText);
        if (selector.isEmpty()) {
            report(id, path.child("on"), "unknown event type '" + typeOf(selectorText) + "'",
                    "see the event taxonomy (runtime-systems §2.1): "
                            + "click, break, place, interact, equip, unequip, drop, pickup, consume, damage, death…");
            return Optional.empty();
        }
        var unit = new CompileUnit(context, "rule '" + id + "'", rootsOf(context.lookup, selector.get()),
                "the '" + selector.get().type() + "' trigger provides");
        var steps = new StepCompiler(unit, flows);
        try {
            Condition condition = condition(rule.get("if"), path.child("if"), steps);
            List<Step> body = steps.list(rule.get("do"), path.child("do"));
            if (body.isEmpty()) {
                throw emptyBody(unit, rule.get("do"), path.child("do"));
            }
            if (steps.deepestCall() > CallStep.MAX_DEPTH) {
                unit.problem(DiagnosticCodes.FLOW_CALL_INVALID, Severity.ERROR, path.child("do"),
                        "calls nest " + steps.deepestCall() + " deep, past the limit of " + CallStep.MAX_DEPTH,
                        "flatten the chain of calls; the rule was skipped");
                throw Skip.unit();
            }
            RuleOptions options = options(rule, unit, path);
            return Optional.of(new Rule(context.owner, id, selector.get(), condition, body, options, path));
        } catch (Skip skip) {
            return Optional.empty();
        }
    }

    private Skip emptyBody(CompileUnit unit, Object declared, StepPath path) {
        List<Object> items = Shapes.listOf(declared);
        unit.problem(DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN, path,
                declared == null ? "missing 'do:' actions"
                        : items.isEmpty() ? "'do:' is empty" : "none of its actions loaded",
                items.isEmpty() ? "add at least one action" : "fix the actions reported above; the rule was skipped");
        return Skip.unit();
    }

    /** The rule-level {@code if:}: one condition or a list, all of which must hold. */
    private Condition condition(Object raw, StepPath path, StepCompiler steps) {
        if (raw == null) {
            return new AllCond(List.of());
        }
        if (!(raw instanceof List<?> list)) {
            return new AllCond(List.of(steps.conditions().compile(raw, path)));
        }
        var conditions = new ArrayList<Condition>();
        boolean failed = false;
        for (int i = 0; i < list.size(); i++) {
            try {
                conditions.add(steps.conditions().compile(list.get(i), path.child(i)));
            } catch (Skip skip) {
                if (skip.scope() == Skip.Scope.UNIT) {
                    throw skip;
                }
                failed = true; // report every bad condition, then skip the rule
            }
        }
        if (failed) {
            throw Skip.unit();
        }
        return new AllCond(conditions);
    }

    // --- options ------------------------------------------------------------------

    private RuleOptions options(Map<String, Object> raw, CompileUnit unit, StepPath path) {
        long cooldown = 0;
        Integer charges = null;
        String scope = RuleOptions.PER_PLAYER;
        if (raw.get("cooldown") instanceof Number number && number.longValue() >= 0) {
            cooldown = number.longValue();
        }
        if (raw.get("charges") instanceof Number number && number.intValue() > 0) {
            charges = number.intValue();
        }
        if (raw.get("per") instanceof String per && !per.isBlank()) {
            if (!per.equals(RuleOptions.PER_PLAYER) && !per.equals(RuleOptions.PER_GLOBAL)) {
                unit.problem(DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN, path.child("per"),
                        "unknown 'per:' scope '" + per + "'", "use player or global; defaulting to player");
            } else {
                scope = per;
            }
        }
        return new RuleOptions(cooldown, charges, scope);
    }

    // --- roots --------------------------------------------------------------------

    /**
     * The variable roots a rule of this trigger may read: the universal ones plus the first segment
     * of every path the trigger's context schema lists. Null (unchecked) when the trigger declares
     * no schema, because "unknown" must not turn into a warning on every variable.
     */
    static Set<String> rootsOf(LogicLookup lookup, EventSelector selector) {
        Optional<TriggerDescription> description = lookup.trigger(selector.type());
        if (description.isEmpty() || description.get().context().isEmpty()) {
            return null;
        }
        var roots = new HashSet<>(CompileUnit.UNIVERSAL_ROOTS);
        for (var variable : description.get().context()) {
            String path = variable.path();
            int dot = path.indexOf('.');
            roots.add(dot < 0 ? path : path.substring(0, dot));
        }
        return roots;
    }

    private void report(String id, StepPath path, String problem, String hint) {
        context.report(DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN, "rule '" + id + "'", path, problem, hint);
    }

    private static String typeOf(String selectorText) {
        int colon = selectorText.indexOf(':');
        return (colon < 0 ? selectorText : selectorText.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
    }
}
