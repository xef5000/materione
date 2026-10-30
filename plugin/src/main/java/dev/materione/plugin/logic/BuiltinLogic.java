package dev.materione.plugin.logic;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicApi;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.behavior.logic.BuiltinTriggers;
import dev.materione.behavior.logic.LogicIds;
import dev.materione.plugin.logic.action.CancelAction;
import dev.materione.plugin.logic.action.ConnectAction;
import dev.materione.plugin.logic.action.GiveMoneyAction;
import dev.materione.plugin.logic.action.GivePermissionAction;
import dev.materione.plugin.logic.action.MessageAction;
import dev.materione.plugin.logic.action.PotionAction;
import dev.materione.plugin.logic.action.RunCommandAction;
import dev.materione.plugin.logic.action.SetVarAction;
import dev.materione.plugin.logic.action.SoundAction;
import dev.materione.plugin.logic.action.TakeMoneyAction;
import dev.materione.plugin.logic.action.TakePermissionAction;
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

    /**
     * The provider-backed actions (logic.md §4.5): money, permissions and server transfer. They
     * read the provider slots on every run, so they are built once against the live
     * {@link LogicProviders}, never against a snapshot of what is installed.
     */
    public static List<ActionType<?>> providerActions(LogicProviders providers) {
        return List.of(
                new GiveMoneyAction(providers),
                new TakeMoneyAction(providers),
                new GivePermissionAction(providers),
                new TakePermissionAction(providers),
                new ConnectAction(providers));
    }

    /** Registers the built-in triggers and actions under {@code materione:}, owned by {@code owner}. */
    public static void register(LogicApi api, Plugin owner) {
        for (TriggerType trigger : BuiltinTriggers.all()) {
            api.triggers().register(owner, builtIn(trigger.id()), trigger);
        }
        for (ActionType<?> action : actions()) {
            api.actions().register(owner, builtIn(action.id()), action);
        }
        for (ActionType<?> action : providerActions(api.providers())) {
            api.actions().register(owner, builtIn(action.id()), action);
        }
    }

    private static NamespacedId builtIn(String id) {
        return NamespacedId.of(LogicIds.BUILT_IN_NAMESPACE, id);
    }
}
