package dev.materione.api.logic;

import dev.materione.api.logic.trace.LogicTraces;
import dev.materione.api.logic.variable.VariablesApi;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.registry.BehaviorVocabulary;

/**
 * The logic sub-API of {@link dev.materione.api.MaterioneAPI} (logic.md §4, §8; reached as
 * {@code api.logic()}): the registries that make up the open vocabulary of the one behavior system
 * (D4), and the self-description the editor draws its palette from.
 *
 * <pre>{@code
 * MaterioneAPI api = getServer().getServicesManager().load(MaterioneAPI.class);
 * api.logic().actions().register(this, NamespacedId.of("example", "greet"), new GreetAction());
 * api.logic().conditions().register(this, NamespacedId.of("example", "is_night"), new IsNight());
 * api.pipeline().reload(ActorContext.addon(getName()), PipelineApi.ReloadRequest.everything());
 * }</pre>
 *
 * <p>Registration changes the vocabulary immediately; content picks the new types up on the next
 * reload, because steps are parsed at load. Nothing here edits files (D1) or runs a flow directly
 * (D6): firing a trigger belongs to the runtime that owns the event.
 *
 * <p>The authoring edit/validate surface (§8) lives on {@code AuthoringApi.logic()} (§13 A1); live run
 * traces (§7.6, §32) are {@link #traces()}.
 */
public interface LogicApi {

    /** The {@code actions} registry: verbs a rule step runs. */
    LogicRegistry<ActionType<?>> actions();

    /** The {@code conditions} registry: predicates a rule or {@code if} step tests. */
    LogicRegistry<ConditionType<?>> conditions();

    /** The {@code triggers} registry: events a rule answers. Descriptions only (see {@link TriggerType}). */
    LogicRegistry<TriggerType> triggers();

    /** The {@code expression-functions} registry: calls available inside expressions. */
    LogicRegistry<ExpressionFunction> functions();

    /**
     * Declared persistent variables (logic.md §3): read the effective value for a player or the
     * server, and write one with an actor check. Reads and writes go through the same stores the
     * flows use.
     */
    VariablesApi variables();

    /**
     * The provider slots the money, permission and transfer actions run against (logic.md §4.5, §18):
     * the currency catalog, the permission provider and the server transfer. Resolved at use, never
     * cached by an action.
     */
    LogicProviders providers();

    /**
     * The {@code currency-integrations} registry: how a currency reaches its money (Vault, XP, a command
     * pair, or an addon's own) (logic.md §18). Register here to make a new integration name available to
     * currency files.
     */
    LogicRegistry<dev.materione.api.logic.provider.CurrencyIntegration> currencyIntegrations();

    /**
     * The {@code item-sources} registry: where a GUI item layer's item comes from (logic.md §4.4). The
     * built-ins are {@code materione:item}, {@code materione:head} and {@code materione:player_slot}; the
     * optional integrations add {@code materione:hdb} and the rest while their plugins run. An addon
     * registers its own under its namespace. A late registration takes effect on the next reload.
     */
    LogicRegistry<ItemSource<?>> itemSources();

    /**
     * The {@code list-sources} registry: where a GUI {@code list} component's entries come from (logic.md §4.5,
     * §6.7). The built-ins are {@code online_players}, {@code pack_items}, {@code variable} and {@code static};
     * an addon registers its own under its namespace. A late registration takes effect on the next reload.
     */
    LogicRegistry<ListSource<?>> listSources();

    /**
     * The {@code input-methods} registry: how a {@code prompt} step asks a player for text (logic.md §4.5, §6.6).
     * The built-ins are {@code materione:chat}, {@code materione:sign} and {@code materione:anvil}; an addon
     * registers its own under its namespace. A late registration takes effect on the next reload.
     */
    LogicRegistry<InputMethod> inputMethods();

    /**
     * The {@code recipes} registry: parameterized fragments the editor inserts and {@code instantiate} expands into
     * ordinary rules (logic.md §4.6, §30). The built-ins are the v1 recipes, {@code materione:pay_to_receive} and the
     * rest; an addon registers its own under its namespace. A recipe is read when it is used, so a late registration
     * is visible at once to the editor and to {@code instantiate}, and it needs no reload.
     */
    LogicRegistry<Recipe> recipes();

    /**
     * Live run traces (logic.md §7.6, §32): subscribe, per player and per resource, to the step-boundary events of
     * the runs a flow produces. Rate-limited and capped, and free while nobody subscribes.
     */
    LogicTraces traces();

    /**
     * The self-description of the whole behavior system as the editor sees it, rebuilt from the
     * registries on every call: triggers (filters, context schema, cancellability, who fires them),
     * actions, conditions, step kinds, categories, expression functions and mechanics, with
     * per-entry availability. This is the version-2 vocabulary: a superset of what version 1
     * carried, so existing consumers read it unchanged.
     */
    BehaviorVocabulary vocabulary();
}
