package dev.materione.behavior.rule;

import dev.materione.api.logic.RuleThrottle;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.behavior.flow.ActionStep;
import java.util.List;

/**
 * The self-description of the keys a rule and an action step carry beside their content: the
 * vocabulary publishes {@link #RULE_OPTIONS} and {@link #ACTION_OPTIONS}, and the flow compiler
 * reads exactly these keys (one schema source, D5). Compiling a rule is
 * {@link dev.materione.behavior.flow.FlowCompiler}'s job; this class holds no logic.
 */
public final class RuleParser {

    /** Self-description of the rule option keys. */
    public static final List<ParameterSchema> RULE_OPTIONS = withId();

    private static List<ParameterSchema> withId() {
        var options = new java.util.ArrayList<ParameterSchema>();
        options.add(option("id", ParameterType.STRING, null,
                "Stable rule name used in diagnostics and cooldown keys", List.of()));
        options.addAll(RuleThrottle.options()); // cooldown, charges, per, on_cooldown: shared with the command document
        return List.copyOf(options);
    }

    /** Self-description of the flow keys of a rule: the condition that guards it and the steps it runs. */
    public static final List<ParameterSchema> RULE_BODY = List.of(
            option("if", ParameterType.RAW, null, "The condition that must hold for the rule to run", List.of())
                    .ofKind(ParameterSchema.KIND_CONDITION),
            new ParameterSchema("do", ParameterType.RAW, true, null, "The steps the rule runs", List.of(), List.of())
                    .ofKind(ParameterSchema.KIND_STEPS));

    /** Self-description of the keys the step of a guarding action (one that takes an else) may carry. */
    public static final List<ParameterSchema> GUARD_OPTIONS = List.of(
            option("else", ParameterType.RAW, null,
                    "Steps that run when the action fails or goes unanswered; the flow then stops", List.of())
                    .ofKind(ParameterSchema.KIND_STEPS));

    /** Self-description of the universal action keys. */
    public static final List<ParameterSchema> ACTION_OPTIONS = List.of(
            option(ActionStep.CHANCE_KEY, ParameterType.DECIMAL, "1",
                    "Probability from 0 to 1 that the action runs", List.of()),
            option(ActionStep.DELAY_KEY, ParameterType.INTEGER, "0",
                    "Ticks to wait before the action runs; the flow waits with it", List.of()));

    private static ParameterSchema option(
            String id, ParameterType type, String defaultValue, String description, List<String> allowed) {
        return new ParameterSchema(id, type, false, defaultValue, description, allowed, List.of());
    }

    private RuleParser() {}
}
