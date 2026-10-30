package dev.materione.plugin.logic;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.ExpressionFunction;
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
import dev.materione.plugin.logic.function.AbsFunction;
import dev.materione.plugin.logic.function.CeilFunction;
import dev.materione.plugin.logic.function.ContainsFunction;
import dev.materione.plugin.logic.function.EndsWithFunction;
import dev.materione.plugin.logic.function.EqualsIgnoreCaseFunction;
import dev.materione.plugin.logic.function.FloorFunction;
import dev.materione.plugin.logic.function.FormatNumberFunction;
import dev.materione.plugin.logic.function.IsNumberFunction;
import dev.materione.plugin.logic.function.IsPlayerFunction;
import dev.materione.plugin.logic.function.LengthFunction;
import dev.materione.plugin.logic.function.LowerFunction;
import dev.materione.plugin.logic.function.MatchesFunction;
import dev.materione.plugin.logic.function.MaxFunction;
import dev.materione.plugin.logic.function.MinFunction;
import dev.materione.plugin.logic.function.NumberFunction;
import dev.materione.plugin.logic.function.RandomFunction;
import dev.materione.plugin.logic.function.RoundFunction;
import dev.materione.plugin.logic.function.StartsWithFunction;
import dev.materione.plugin.logic.function.TrimFunction;
import dev.materione.plugin.logic.function.UpperFunction;
import java.util.List;
import org.bukkit.plugin.Plugin;

/**
 * Materione's own logic vocabulary, registered through the public {@link LogicApi} and nothing
 * else (D5): the same calls an addon makes, so the built-ins are proof that the SPI is enough.
 * The built-in conditions arrive with their own work (L4) and are added to this list then.
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

    /** The built-in expression functions (logic.md §4.2): pure, typed, and callable from any expression. */
    public static List<ExpressionFunction> functions() {
        return List.of(
                new ContainsFunction(),
                new EqualsIgnoreCaseFunction(),
                new StartsWithFunction(),
                new EndsWithFunction(),
                new MatchesFunction(),
                new LengthFunction(),
                new LowerFunction(),
                new UpperFunction(),
                new TrimFunction(),
                new IsNumberFunction(),
                new IsPlayerFunction(),
                new NumberFunction(),
                new RoundFunction(),
                new FloorFunction(),
                new CeilFunction(),
                new MinFunction(),
                new MaxFunction(),
                new AbsFunction(),
                new RandomFunction(),
                new FormatNumberFunction());
    }

    /**
     * Registers the built-in triggers, actions and expression functions under {@code materione:},
     * owned by {@code owner}.
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
        for (ExpressionFunction function : functions()) {
            api.functions().register(owner, builtIn(function.id()), function);
        }
    }

    private static NamespacedId builtIn(String id) {
        return NamespacedId.of(LogicIds.BUILT_IN_NAMESPACE, id);
    }
}
