package dev.materione.behavior.rule;

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
    public static final List<ParameterSchema> RULE_OPTIONS = List.of(
            option("id", ParameterType.STRING, null,
                    "Stable rule name used in diagnostics and cooldown keys", List.of()),
            option("cooldown", ParameterType.INTEGER, "0",
                    "Milliseconds before this rule can fire again", List.of()),
            option("charges", ParameterType.INTEGER, null,
                    "How many times the rule can fire in total", List.of()),
            option("per", ParameterType.ENUM, RuleOptions.PER_PLAYER,
                    "Whether cooldown and charges count per player or for the whole server",
                    List.of(RuleOptions.PER_PLAYER, RuleOptions.PER_GLOBAL)));

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
