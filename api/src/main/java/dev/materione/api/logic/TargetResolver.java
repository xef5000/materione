package dev.materione.api.logic;

import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Turns a {@link TargetSpec} into the subjects an action runs on (logic.md §39). The platform supplies the one
 * implementation (the engine is Bukkit-free); every targetable action reaches it through the engine, so no action
 * parses or resolves a target itself.
 *
 * <p><b>Contract.</b> {@link #resolve} never throws and never blocks: a refusal is a {@link TargetResolution} with a
 * reason, and anything that must read a world (a nearby search) goes through {@code reads} to the thread that owns
 * it. It resolves only what is online and loaded now; an offline player is a refusal in this version. The returned
 * subjects are each owned by their own region, so the engine, not the resolver, dispatches the action to it.
 */
public interface TargetResolver {

    /**
     * Resolves {@code spec} for the run {@code context} describes.
     *
     * @param spec the parsed target; never {@link TargetSpec.Self}
     * @param admits the kinds the action accepts; a selector filters by them, a single target of another kind is
     *        refused
     * @param context the run's context (its player, its event target, its arguments)
     * @param reads where a read of the run's own state, or of the world, is dispatched
     */
    CompletionStage<TargetResolution> resolve(
            TargetSpec spec, Set<TargetKind> admits, LogicContext context, ReadExecutor reads);
}
