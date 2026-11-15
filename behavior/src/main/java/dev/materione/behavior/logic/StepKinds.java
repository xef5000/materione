package dev.materione.behavior.logic;

import dev.materione.api.registry.BehaviorVocabulary.Grammar;
import dev.materione.api.registry.BehaviorVocabulary.StepKind;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterSchema.EditorMetadata;
import dev.materione.api.registry.ParameterType;
import dev.materione.behavior.rule.RuleParser;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The engine-owned step kinds (logic.md §2.2): a closed set, because control flow belongs to the
 * engine and only actions, conditions and triggers are open registries. A small fixed grammar is
 * what makes the node graph (§7.2) total and lossless.
 *
 * <p>Every kind is listed so the studio can draw the whole grammar; {@code supported} says which
 * the running engine executes. Since the flow interpreter (#208) that is all seven; the flag stays so
 * a future kind can be listed before the engine runs it.
 *
 * <p><b>The grammar is data (issue 355).</b> Each kind says which parameter is the value of its own key
 * ({@code payload}), which are keys beside it ({@code siblings}) and what each holds (a list of steps, a
 * condition, a flow name). The step compiler reads its control keys and their companions from here
 * ({@link #controlKeys()}, {@link #companions()}), so the description is the compiler's own table and not a
 * copy of it; the set stays closed, it only describes itself.
 */
public final class StepKinds {

    /** One day of ticks: the longest wait and delay the engine accepts ({@code WaitStep.MAX_TICKS}). */
    public static final long MAX_TICKS = 24L * 60 * 60 * 20;

    private static final String ACTIONS = "actions";

    private StepKinds() {}

    public static List<StepKind> all() {
        return List.of(
                new StepKind("action", "Action",
                        "Runs a registered action, optionally with a chance or a delay. A failure never aborts the flow.",
                        RuleParser.ACTION_OPTIONS, true, "", List.of(), List.of(), ACTIONS),
                new StepKind("if", "If",
                        "Runs one list of steps when a condition holds and another when it does not. At least one of "
                                + "then and else is written; the flow continues after it.",
                        List.of(condition("if", "The condition", true), steps("then", "Steps run when it holds", false),
                                steps("else", "Steps run when it does not", false)),
                        true, "if", List.of("then", "else"), List.of("then", "else"), ""),
                new StepKind("require", "Require",
                        "Runs steps in order until one fails; then runs the else steps and stops the flow. It takes "
                                + "steps, not conditions: guard with if and stop for a condition.",
                        List.of(steps("require", "Steps that must all succeed", true),
                                steps("else", "Steps run when one fails", false)),
                        true, "require", List.of("else"), List.of(), ""),
                new StepKind("wait", "Wait",
                        "Suspends the flow for a number of ticks without blocking a thread.",
                        List.of(ParameterSchema.of("ticks", ParameterType.INTEGER, true).withEditor(
                                EditorMetadata.defaults("ticks", ParameterType.INTEGER)
                                        .withRange(0d, (double) MAX_TICKS, "ticks"))),
                        true, "ticks", List.of(), List.of(), ""),
                new StepKind("stop", "Stop",
                        "Ends this run, optionally cancelling the underlying event.",
                        List.of(ParameterSchema.of("cancel", ParameterType.BOOLEAN, false),
                                ParameterSchema.of("cancel_session_runs", ParameterType.BOOLEAN, false)), true),
                new StepKind("call", "Call",
                        "Runs a named flow of the same resource inline.",
                        List.of(new ParameterSchema("flow", ParameterType.STRING, true, null,
                                        "The name of a flow declared under flows:", List.of(), List.of())
                                        .ofKind(ParameterSchema.KIND_FLOW),
                                raw("with", "Arguments the flow reads as args.*").ofKind(ParameterSchema.KIND_ARGUMENTS)),
                        true, "flow", List.of("with"), List.of(), ""),
                new StepKind("choose", "Choose",
                        "Runs the first branch whose condition holds.",
                        List.of(new ParameterSchema("choose", ParameterType.LIST, true, null,
                                        "The branches, each an if/then pair; at least one", List.of(),
                                        List.of(condition("if", "The branch's condition", true),
                                                steps("then", "Steps run when it is the first that holds", true))),
                                steps("else", "Steps run when no branch holds", false)),
                        true, "choose", List.of("else"), List.of(), ""));
    }

    /**
     * The composite conditions (logic.md §2.3), in the shape of a step kind: closed and engine-owned like the
     * steps, and compiled by {@code ConditionCompiler} under exactly these keys.
     */
    public static List<StepKind> conditions() {
        return List.of(
                new StepKind("all", "All", "Holds when every condition of the list holds.",
                        List.of(conditions("all", "The conditions")), true, "all", List.of(), List.of(), ""),
                new StepKind("any", "Any", "Holds when at least one condition of the list holds.",
                        List.of(conditions("any", "The conditions")), true, "any", List.of(), List.of(), ""),
                new StepKind("not", "Not", "Holds when the condition does not.",
                        List.of(condition("not", "The condition", true)), true, "not", List.of(), List.of(), ""),
                new StepKind("at_least", "At least", "Holds when at least count conditions of the list hold.",
                        List.of(ParameterSchema.of("count", ParameterType.INTEGER, true).withEditor(
                                        EditorMetadata.defaults("count", ParameterType.INTEGER).withRange(0d, null, "")),
                                conditions("of", "The conditions")), true));
    }

    /** The grammar that is neither a step kind nor a registry entry (issue 355). */
    public static Grammar grammar() {
        return new Grammar(conditions(), RuleParser.RULE_BODY, RuleParser.GUARD_OPTIONS);
    }

    /** The keys that make a step a control step: every kind written by its own key. */
    public static Set<String> controlKeys() {
        var keys = new LinkedHashSet<String>();
        for (StepKind kind : all()) {
            if (kind.registry().isEmpty()) {
                keys.add(kind.id());
            }
        }
        return Set.copyOf(keys);
    }

    /** The keys each control step may carry beside its own. */
    public static Map<String, Set<String>> companions() {
        var out = new LinkedHashMap<String, Set<String>>();
        for (StepKind kind : all()) {
            if (kind.registry().isEmpty()) {
                out.put(kind.id(), Set.copyOf(kind.siblings()));
            }
        }
        return Map.copyOf(out);
    }

    private static ParameterSchema raw(String id, String description) {
        return new ParameterSchema(id, ParameterType.RAW, false, null, description, List.of(), List.of());
    }

    private static ParameterSchema steps(String id, String description, boolean required) {
        return new ParameterSchema(id, ParameterType.RAW, required, null, description, List.of(), List.of())
                .ofKind(ParameterSchema.KIND_STEPS);
    }

    private static ParameterSchema condition(String id, String description, boolean required) {
        return new ParameterSchema(id, ParameterType.RAW, required, null, description, List.of(), List.of())
                .ofKind(ParameterSchema.KIND_CONDITION);
    }

    private static ParameterSchema conditions(String id, String description) {
        return new ParameterSchema(id, ParameterType.RAW, true, null, description, List.of(), List.of())
                .ofKind(ParameterSchema.KIND_CONDITIONS);
    }
}
