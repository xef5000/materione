package dev.materione.plugin.logic;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicApi;
import dev.materione.api.logic.TriggerType;
import dev.materione.behavior.logic.BuiltinTriggers;
import dev.materione.behavior.logic.LogicIds;
import dev.materione.plugin.logic.action.ActionbarAction;
import dev.materione.plugin.logic.action.BroadcastAction;
import dev.materione.plugin.logic.action.CancelAction;
import dev.materione.plugin.logic.action.ChatAction;
import dev.materione.plugin.logic.action.GiveExpAction;
import dev.materione.plugin.logic.action.GiveItemAction;
import dev.materione.plugin.logic.action.MessageAction;
import dev.materione.plugin.logic.action.PotionAction;
import dev.materione.plugin.logic.action.RunCommandAction;
import dev.materione.plugin.logic.action.SetVarAction;
import dev.materione.plugin.logic.action.SoundAction;
import dev.materione.plugin.logic.action.StopSoundAction;
import dev.materione.plugin.logic.action.TakeExpAction;
import dev.materione.plugin.logic.action.TakeItemAction;
import dev.materione.plugin.logic.action.TeleportAction;
import dev.materione.plugin.logic.action.TitleAction;
import dev.materione.plugin.logic.support.ItemCatalog;
import java.util.ArrayList;
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

    /**
     * The built-in actions. {@code items} is the live item registry the item actions resolve
     * Materione ids against; pass {@link ItemCatalog#none()} where no content is loaded.
     */
    public static List<ActionType<?>> actions(ItemCatalog items) {
        var actions = new ArrayList<ActionType<?>>(List.of(
                new RunCommandAction(),
                new MessageAction(),
                new SoundAction(),
                new PotionAction(),
                new CancelAction(),
                new SetVarAction()));
        // #212 (L6): player, world and server actions. One class each; no action depends on another.
        actions.addAll(List.of(
                new BroadcastAction(),
                new ActionbarAction(),
                new TitleAction(),
                new StopSoundAction(),
                new ChatAction(),
                new TeleportAction(),
                new GiveItemAction(items),
                new TakeItemAction(items),
                new GiveExpAction(),
                new TakeExpAction()));
        return List.copyOf(actions);
    }

    /**
     * Registers the built-in triggers and actions under {@code materione:}, owned by {@code owner}.
     *
     * @param items the live item registries for the item actions (see {@link #actions(ItemCatalog)})
     */
    public static void register(LogicApi api, Plugin owner, ItemCatalog items) {
        for (TriggerType trigger : BuiltinTriggers.all()) {
            api.triggers().register(owner, builtIn(trigger.id()), trigger);
        }
        for (ActionType<?> action : actions(items)) {
            api.actions().register(owner, builtIn(action.id()), action);
        }
    }

    private static NamespacedId builtIn(String id) {
        return NamespacedId.of(LogicIds.BUILT_IN_NAMESPACE, id);
    }
}
