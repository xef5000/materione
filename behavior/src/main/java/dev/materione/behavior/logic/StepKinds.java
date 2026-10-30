package dev.materione.behavior.logic;

import dev.materione.api.registry.BehaviorVocabulary.StepKind;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.behavior.rule.RuleParser;
import java.util.List;

/**
 * The engine-owned step kinds (logic.md §2.2): a closed set, because control flow belongs to the
 * engine and only actions, conditions and triggers are open registries. A small fixed grammar is
 * what makes the node graph (§7.2) total and lossless.
 *
 * <p>Every kind is listed so the studio can draw the whole grammar; {@code supported} says which
 * the running engine executes. Since the flow interpreter (#208) that is all seven; the flag stays so
 * a future kind can be listed before the engine runs it.
 */
public final class StepKinds {

    private StepKinds() {}

    public static List<StepKind> all() {
        return List.of(
                new StepKind("action", "Action",
                        "Runs a registered action, optionally with a chance or a delay. A failure never aborts the flow.",
                        RuleParser.ACTION_OPTIONS, true),
                new StepKind("if", "If",
                        "Runs one list of steps when a condition holds and another when it does not.",
                        List.of(raw("if", "The condition"), raw("then", "Steps run when it holds"),
                                raw("else", "Steps run when it does not")), true),
                new StepKind("require", "Require",
                        "Runs steps in order until one fails; then runs the else steps and stops the flow.",
                        List.of(raw("require", "Steps that must all succeed"),
                                raw("else", "Steps run when one fails")), true),
                new StepKind("wait", "Wait",
                        "Suspends the flow for a number of ticks without blocking a thread.",
                        List.of(ParameterSchema.of("ticks", ParameterType.INTEGER, true)), true),
                new StepKind("stop", "Stop",
                        "Ends this run, optionally cancelling the underlying event.",
                        List.of(ParameterSchema.of("cancel", ParameterType.BOOLEAN, false),
                                ParameterSchema.of("cancel_session_runs", ParameterType.BOOLEAN, false)), true),
                new StepKind("call", "Call",
                        "Runs a named flow of the same resource inline.",
                        List.of(ParameterSchema.of("flow", ParameterType.STRING, true),
                                raw("with", "Arguments the flow reads as args.*")), true),
                new StepKind("choose", "Choose",
                        "Runs the first branch whose condition holds.",
                        List.of(raw("choose", "The branches, each an if/then pair"),
                                raw("else", "Steps run when no branch holds")), true));
    }

    private static ParameterSchema raw(String id, String description) {
        return new ParameterSchema(id, ParameterType.RAW, false, null, description, List.of(), List.of());
    }
}
