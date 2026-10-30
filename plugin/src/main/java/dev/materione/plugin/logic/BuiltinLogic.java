package dev.materione.plugin.logic;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicApi;
import dev.materione.api.logic.TriggerType;
import dev.materione.behavior.logic.BuiltinTriggers;
import dev.materione.behavior.logic.LogicIds;
import dev.materione.plugin.logic.action.CancelAction;
import dev.materione.plugin.logic.action.MessageAction;
import dev.materione.plugin.logic.action.PotionAction;
import dev.materione.plugin.logic.action.RunCommandAction;
import dev.materione.plugin.logic.action.SetVarAction;
import dev.materione.plugin.logic.action.SoundAction;
import java.util.List;
import org.bukkit.plugin.Plugin;

/**
 * Materione's own logic vocabulary, registered through the public {@link LogicApi} and nothing
 * else (D5): the same calls an addon makes, so the built-ins are proof that the SPI is enough.
 * The conditions and the expression functions of logic.md §4.2 arrive with their own work and are
 * added to this list then.
 */
public final class BuiltinLogic {

    private BuiltinLogic() {}

    /** The built-in actions: the six M2 verbs. */
    public static List<ActionType<?>> actions() {
        return List.of(
                new RunCommandAction(),
                new MessageAction(),
                new SoundAction(),
                new PotionAction(),
                new CancelAction(),
                new SetVarAction());
    }

    /** Registers the built-in triggers and actions under {@code materione:}, owned by {@code owner}. */
    public static void register(LogicApi api, Plugin owner) {
        for (TriggerType trigger : BuiltinTriggers.all()) {
            api.triggers().register(owner, builtIn(trigger.id()), trigger);
        }
        for (ActionType<?> action : actions()) {
            api.actions().register(owner, builtIn(action.id()), action);
        }
    }

    private static NamespacedId builtIn(String id) {
        return NamespacedId.of(LogicIds.BUILT_IN_NAMESPACE, id);
    }
}
