package dev.materione.api.logic;

import dev.materione.api.logic.variable.VariablesApi;
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
 * <h2>Planned additions</h2>
 *
 * <p>{@code traces()} (subscribe to run traces, logic.md §7.6, §13 W4) is deliberately absent and
 * arrives as an additive method when its subsystem exists, never as a placeholder. Recipes and the
 * authoring edit/validate surface (§8) live on {@code AuthoringApi.logic()} (§13 A1).
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
     * The self-description of the whole behavior system as the editor sees it, rebuilt from the
     * registries on every call: triggers (filters, context schema, cancellability, who fires them),
     * actions, conditions, step kinds, categories, expression functions and mechanics, with
     * per-entry availability. This is the version-2 vocabulary: a superset of what version 1
     * carried, so existing consumers read it unchanged.
     */
    BehaviorVocabulary vocabulary();
}
