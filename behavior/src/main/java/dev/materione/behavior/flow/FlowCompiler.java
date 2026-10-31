package dev.materione.behavior.flow;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.behavior.rule.EventSelector;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.RuleFilters;
import dev.materione.behavior.rule.RuleOptions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a resource's {@code behavior:} and {@code flows:} sections into loaded rules (logic.md
 * §5.1). Compilation is pure: problems come back as diagnostics for the caller to publish, and
 * the compiler never touches the bus, the filesystem or the platform. It runs in the pipeline, once
 * per resource per load.
 *
 * <p><b>What it guarantees.</b>
 *
 * <ul>
 *   <li>Every action and condition is resolved through the registries and parsed once, so a rule
 *       holds typed steps, not raw maps (§4.1).
 *   <li>Every diagnostic carries the {@link StepPath} of what it is about, as its target
 *       ({@code behavior.2.do.0.then.1}), so the studio and {@code validate} can point at a node.
 *   <li>Every {@code ${root}} is checked against the rule's trigger schema (§2.6): an unknown root
 *       is a warning and changes nothing else.
 *   <li>A {@code call} is linked to its flow here; unknown flows, cycles and chains deeper than
 *       {@link CallStep#MAX_DEPTH} skip the rule that holds them.
 *   <li>Fail-soft with a fail-closed edge: one broken rule never costs its siblings, one broken step
 *       never costs its rule, but a step that guards others ({@code require}, {@code choose}) never
 *       lets its failure widen what the survivors do.
 *   <li>Nothing the author wrote is altered: the input is only read, so a load, compile and
 *       untouched save is byte-identical (master §5.6). {@code node: {x, y}} hints are accepted and
 *       ignored.
 * </ul>
 */
public final class FlowCompiler {

    private FlowCompiler() {}

    /**
     * Compiles a resource's authored sections. Never returns null; invalid rules and flows surface
     * in {@link ParsedRules#problems()}.
     *
     * @param owner the resource that declares them
     * @param behavior the {@code behavior:} value (a list of rule maps), or anything for none
     * @param flows the {@code flows:} value (a map of flow id to steps), or null
     * @param lookup the registries actions, conditions and triggers are resolved against
     */
    public static ParsedRules compile(NamespacedId owner, Object behavior, Object flows, LogicLookup lookup) {
        return compile(new CompileContext(owner, lookup, true), behavior, flows);
    }

    /**
     * Compiles one condition outside a rule: a GUI layer's {@code visible_if} (logic.md §6.3). It takes the
     * same shapes a {@code require} or {@code if} takes (an expression, a predicate, a composite), and its
     * {@code ${root}} paths are checked against {@code roots} (null checks nothing). Pure, like {@link #compile}.
     *
     * @param owner the resource that declares the condition
     * @param subject what the condition belongs to, for messages ({@code gui shop layer buy})
     * @param raw what the author wrote
     * @param roots the variable roots the condition may read, or null
     * @param lookup the registries predicates and functions resolve against
     */
    public static CompiledCondition compileCondition(
            NamespacedId owner, String subject, Object raw, Set<String> roots, LogicLookup lookup) {
        var context = new CompileContext(owner, lookup, false);
        var unit = new CompileUnit(context, subject, roots, "in this condition");
        Condition compiled = null;
        try {
            compiled = new ConditionCompiler(unit).compile(raw, StepPath.root());
        } catch (Skip skipped) {
            // the problem is already reported to the context
        }
        return new CompiledCondition(Optional.ofNullable(compiled), List.copyOf(context.problems));
    }

    /**
     * A condition compiled outside a rule.
     *
     * @param condition the compiled tree, or empty when the condition is unusable
     * @param problems why it is unusable, or what it reads that the roots do not provide
     */
    public record CompiledCondition(Optional<Condition> condition, List<dev.materione.api.diagnostics.Diagnostic> problems) {

        public CompiledCondition {
            problems = List.copyOf(problems);
        }
    }

    /** {@link #compile(NamespacedId, Object, Object, LogicLookup)} for a resource without named flows. */
    public static ParsedRules compile(NamespacedId owner, Object behavior, LogicLookup lookup) {
        return compile(owner, behavior, null, lookup);
    }

    /**
     * Compiles rules a mechanic generated rather than an author wrote. They are not in the
     * resource's file, so their diagnostics name the rule but carry no field target.
     */
    public static ParsedRules compileGenerated(NamespacedId owner, Object rules, LogicLookup lookup) {
        return compile(new CompileContext(owner, lookup, false), rules, null);
    }

    /** The outcome of compiling one condition: the condition, or null when it did not load. */
    public record ParsedCondition(Condition condition, List<Diagnostic> problems) {}

    /**
     * Compiles one condition as the author wrote it (an expression, a predicate, or a composite), checked
     * against the variable roots of {@code trigger}. Used for a GUI's {@code open.requirement} (logic.md
     * §6.1): the problems name {@code open.requirement}, and a condition that did not load comes back null,
     * which the caller treats as a failed check.
     */
    public static ParsedCondition condition(NamespacedId owner, Object raw, String trigger, LogicLookup lookup) {
        var context = new CompileContext(owner, lookup, true);
        var unit = new CompileUnit(context, "open", rootsOf(lookup, trigger), "the '" + trigger + "' trigger provides");
        try {
            Condition condition = new ConditionCompiler(unit).compile(raw, StepPath.of("open", "requirement"));
            return new ParsedCondition(condition, List.copyOf(context.problems));
        } catch (Skip skip) {
            return new ParsedCondition(null, List.copyOf(context.problems));
        }
    }

    /**
     * Compiles the steps of an {@code open.deny} list as one rule of {@code trigger} with no condition, so
     * the caller can run it through the interpreter when a requirement fails (logic.md §6.1). Only the
     * flows its {@code call}s reach are compiled here: the GUI's own rules already report the others.
     *
     * @return the rule, or no rule when the steps did not load (the problems say why)
     */
    public static ParsedRules denied(NamespacedId owner, String trigger, Object steps, Object flows, LogicLookup lookup) {
        var context = new CompileContext(owner, lookup, true);
        Optional<EventSelector> selector = lookup.selector(trigger);
        if (selector.isEmpty()) {
            return ParsedRules.empty();
        }
        Set<String> roots = rootsOf(lookup, trigger);
        var unit = new CompileUnit(context, "open.deny", roots, "the '" + trigger + "' trigger provides");
        var compiler = new StepCompiler(unit, new FlowSet(context, flows, roots));
        StepPath path = StepPath.of("open", "deny");
        try {
            List<Step> body = compiler.list(steps, path);
            if (body.isEmpty()) {
                return new ParsedRules(List.of(), List.copyOf(context.problems));
            }
            if (compiler.deepestCall() > CallStep.MAX_DEPTH) {
                context.report(DiagnosticCodes.FLOW_CALL_INVALID, Severity.ERROR, "open.deny", path,
                        "calls nest " + compiler.deepestCall() + " deep, past the limit of " + CallStep.MAX_DEPTH,
                        "flatten the chain of calls; the deny steps were dropped");
                return new ParsedRules(List.of(), List.copyOf(context.problems));
            }
            var rule = new Rule(owner, "open.deny", selector.get(), new AllCond(List.of()), body,
                    RuleOptions.none(), RuleFilters.none(), path);
            return new ParsedRules(List.of(rule), List.copyOf(context.problems));
        } catch (Skip skip) {
            return new ParsedRules(List.of(), List.copyOf(context.problems));
        }
    }

    /** The variable roots a condition or step of {@code trigger} may read, or null when the trigger has no schema. */
    private static Set<String> rootsOf(LogicLookup lookup, String trigger) {
        return lookup.selector(trigger).map(selector -> RuleCompiler.rootsOf(lookup, selector)).orElse(null);
    }

    private static ParsedRules compile(CompileContext context, Object behavior, Object flowsSection) {
        List<?> entries = behavior instanceof List<?> list ? list : List.of();
        if (entries.isEmpty() && flowsSection == null) {
            return ParsedRules.empty();
        }
        var flows = new FlowSet(context, flowsSection, flowRoots(context.lookup, entries));
        var compiler = new RuleCompiler(context, flows);
        var rules = new ArrayList<Rule>();
        for (int index = 0; index < entries.size(); index++) {
            compiler.compile(index, entries.get(index)).ifPresent(rules::add);
        }
        flows.compileAll();
        return new ParsedRules(List.copyOf(rules), List.copyOf(context.problems));
    }

    /**
     * The roots a flow body may read: everything any rule of the resource's triggers provides, plus
     * {@code args}. A flow is shared by rules with different triggers, so it is checked against the
     * union and never against one. Null (unchecked) when any rule's trigger declares no schema, or
     * there are no rules to learn a trigger from.
     */
    private static Set<String> flowRoots(LogicLookup lookup, List<?> entries) {
        var union = new HashSet<String>(CompileUnit.UNIVERSAL_ROOTS);
        union.add("args");
        boolean any = false;
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> raw) || !(raw.get("on") instanceof String on) || on.isBlank()) {
                continue;
            }
            Optional<EventSelector> selector = lookup.selector(on);
            if (selector.isEmpty()) {
                continue;
            }
            Set<String> roots = RuleCompiler.rootsOf(lookup, selector.get());
            if (roots == null) {
                return null;
            }
            union.addAll(roots);
            any = true;
        }
        return any ? union : null;
    }
}
