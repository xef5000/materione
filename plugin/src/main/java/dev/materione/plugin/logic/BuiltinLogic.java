package dev.materione.plugin.logic;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.LogicApi;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.behavior.logic.BuiltinTriggers;
import dev.materione.plugin.logic.currency.CustomCurrencyIntegration;
import dev.materione.plugin.logic.currency.XpCurrencyIntegration;
import dev.materione.plugin.logic.function.BalanceFunction;
import dev.materione.plugin.logic.function.FormatCurrencyFunction;
import dev.materione.behavior.logic.LogicIds;
import dev.materione.plugin.gui.GuiBridge;
import dev.materione.plugin.gui.trigger.GuiTriggers;
import dev.materione.plugin.logic.action.ActionbarAction;
import dev.materione.plugin.logic.action.BroadcastAction;
import dev.materione.plugin.logic.action.CancelAction;
import dev.materione.plugin.logic.action.ConnectAction;
import dev.materione.plugin.logic.action.GiveMoneyAction;
import dev.materione.plugin.logic.action.GivePermissionAction;
import dev.materione.plugin.logic.action.ChatAction;
import dev.materione.plugin.logic.action.GiveExpAction;
import dev.materione.plugin.logic.action.GiveItemAction;
import dev.materione.plugin.logic.action.MessageAction;
import dev.materione.plugin.logic.action.PotionAction;
import dev.materione.plugin.logic.action.RunCommandAction;
import dev.materione.plugin.logic.action.SetVarAction;
import dev.materione.plugin.logic.action.SoundAction;
import dev.materione.plugin.logic.action.TakeMoneyAction;
import dev.materione.plugin.logic.action.TakePermissionAction;
import dev.materione.plugin.logic.action.StopSoundAction;
import dev.materione.plugin.logic.action.TakeExpAction;
import dev.materione.plugin.logic.action.TakeItemAction;
import dev.materione.plugin.logic.action.TeleportAction;
import dev.materione.plugin.logic.action.TitleAction;
import dev.materione.plugin.logic.action.gui.BackGuiAction;
import dev.materione.plugin.logic.action.gui.CloseGuiAction;
import dev.materione.plugin.logic.action.gui.OpenGuiAction;
import dev.materione.plugin.logic.action.gui.GuiSlotItemAction;
import dev.materione.plugin.logic.action.gui.RefreshGuiAction;
import dev.materione.plugin.logic.condition.SlotHasCondition;
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
     * The GUI actions (logic.md §6): open, close, back and refresh. They reach the GUI runtime through
     * {@code bridge}, which the runtime binds itself into once it exists.
     */
    public static List<ActionType<?>> guiActions(GuiBridge bridge) {
        return List.of(
                new OpenGuiAction(bridge),
                new CloseGuiAction(bridge),
                new BackGuiAction(bridge),
                new RefreshGuiAction(bridge),
                new GuiSlotItemAction(bridge));
    }

    /**
     * The built-in GUI conditions (logic.md §25): {@code slot_has}, which reads an input slot of the GUI the run
     * belongs to. Each is one class; it reads the GUI runtime through {@code bridge}, so it answers false until
     * the runtime binds.
     */
    public static List<ConditionType<?>> guiConditions(GuiBridge bridge) {
        return List.of(new SlotHasCondition(bridge));
    }

    /**
     * Registers the built-in triggers, actions and expression functions under {@code materione:},
     * owned by {@code owner}. The GUI actions are bound to a bridge nothing binds, so they fail soft
     * ("the GUI runtime is not available"); the plugin uses the overload that passes its own.
     *
     * @param items the live item registries for the item actions (see {@link #actions(ItemCatalog)})
     */
    public static void register(LogicApi api, Plugin owner, ItemCatalog items) {
        register(api, owner, items, new GuiBridge());
    }

    /** As above, with the bridge the GUI actions reach the GUI runtime through. */
    public static void register(LogicApi api, Plugin owner, ItemCatalog items, GuiBridge bridge) {
        for (TriggerType trigger : BuiltinTriggers.all()) {
            api.triggers().register(owner, builtIn(trigger.id()), trigger);
        }
        for (TriggerType trigger : GuiTriggers.all()) {
            api.triggers().register(owner, builtIn(trigger.id()), trigger);
        }
        for (ActionType<?> action : actions(items)) {
            api.actions().register(owner, builtIn(action.id()), action);
        }
        for (ActionType<?> action : guiActions(bridge)) {
            api.actions().register(owner, builtIn(action.id()), action);
        }
        for (ConditionType<?> condition : guiConditions(bridge)) {
            api.conditions().register(owner, builtIn(condition.id()), condition);
        }
        for (ExpressionFunction function : functions()) {
            api.functions().register(owner, builtIn(function.id()), function);
        }
        for (ActionType<?> action : providerActions(api.providers())) {
            api.actions().register(owner, builtIn(action.id()), action);
        }
        // currency integrations and the money functions (logic.md §18): the built-in block
        for (CurrencyIntegration integration : currencyIntegrations()) {
            api.currencyIntegrations().register(owner, builtIn(integration.id()), integration);
        }
        for (ExpressionFunction function : currencyFunctions(api.providers())) {
            api.functions().register(owner, builtIn(function.id()), function);
        }
    }

    /**
     * The built-in currency integrations (logic.md §18): {@code xp} and {@code custom}. Vault is not
     * here: it is a soft-depend adapter in {@code integrations}, registered only while Vault runs.
     */
    public static List<CurrencyIntegration> currencyIntegrations() {
        return List.of(new XpCurrencyIntegration(), new CustomCurrencyIntegration());
    }

    /** The money expression functions, which read the currency catalog (logic.md §18). */
    public static List<ExpressionFunction> currencyFunctions(LogicProviders providers) {
        return List.of(new BalanceFunction(providers), new FormatCurrencyFunction(providers));
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

    private static NamespacedId builtIn(String id) {
        return NamespacedId.of(LogicIds.BUILT_IN_NAMESPACE, id);
    }
}
