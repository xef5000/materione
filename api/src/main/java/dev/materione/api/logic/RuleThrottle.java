package dev.materione.api.logic;

import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import java.util.List;

/**
 * The throttle keys of a flow and their self-description, declared once (logic.md §16, §40): {@code cooldown},
 * {@code charges}, {@code per} and {@code on_cooldown}. A rule carries them beside its {@code do:}; a {@code command}
 * document carries the same four, with the same meaning, so the rule's own vocabulary
 * ({@code RuleParser.RULE_OPTIONS}) and the command's content type both list exactly these entries and cannot drift.
 * The behavior module reads the keys ({@code RuleOptions}); this class holds only their names and their description.
 */
public final class RuleThrottle {

    /** Charge and cooldown state counts per player (the default). */
    public static final String PER_PLAYER = "player";

    /** Charge and cooldown state is one for the whole server. */
    public static final String PER_GLOBAL = "global";

    /** The key of the flow that runs when the throttle matched but its cooldown blocked it. */
    public static final String ON_COOLDOWN = "on_cooldown";

    /** The context root an {@code on_cooldown} run reads; its only member is {@link #REMAINING}. */
    public static final String COOLDOWN_ROOT = "cooldown";

    /** {@code cooldown.remaining}: the whole seconds left in the window, rounded up. */
    public static final String REMAINING = "remaining";

    private RuleThrottle() {}

    /** The four keys, in the order the rule shows them: cooldown, charges, per, on_cooldown. */
    public static List<ParameterSchema> options() {
        return List.of(
                option("cooldown", ParameterType.INTEGER, "0", "Milliseconds before this rule can fire again", List.of()),
                option("charges", ParameterType.INTEGER, null, "How many times the rule can fire in total", List.of()),
                option("per", ParameterType.ENUM, PER_PLAYER,
                        "Whether cooldown and charges count per player or for the whole server",
                        List.of(PER_PLAYER, PER_GLOBAL)),
                // a list of steps, edited as JSON by the studio (raw): cooldown.remaining holds the whole seconds left
                option(ON_COOLDOWN, ParameterType.RAW, null,
                        "Steps that run when the rule matched but its cooldown blocked it; cooldown.remaining is the "
                                + "whole seconds left, rounded up",
                        List.of())
                        .ofKind(ParameterSchema.KIND_STEPS)
                        .opening(List.of(ContextVariable.of(COOLDOWN_ROOT + "." + REMAINING,
                                ParameterType.INTEGER, "The whole seconds left in the cooldown window, rounded up"))));
    }

    private static ParameterSchema option(
            String id, ParameterType type, String defaultValue, String description, List<String> allowed) {
        return new ParameterSchema(id, type, false, defaultValue, description, allowed, List.of());
    }
}
