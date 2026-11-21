package dev.materione.plugin.logic;

import dev.materione.api.logic.ActionType;
import dev.materione.plugin.logic.action.BreakBlocksAction;
import dev.materione.plugin.logic.action.DamageAction;
import dev.materione.plugin.logic.action.DropItemAction;
import dev.materione.plugin.logic.action.HealAction;
import dev.materione.plugin.logic.action.LaunchAction;
import dev.materione.plugin.logic.action.ParticleAction;
import dev.materione.plugin.logic.action.SetBlockAction;
import dev.materione.plugin.logic.action.StackEditAction;
import dev.materione.plugin.logic.action.VelocityAction;
import dev.materione.plugin.logic.support.ItemCatalog;
import dev.materione.plugin.logic.world.WorldVerbs;
import java.util.List;

/**
 * The entity and world verbs (logic.md §41, #329): nine universal primitives, each one class in
 * {@code plugin.logic.action}. They reach the world only through {@code world}, the seam that carries the thread
 * gateway, the break-as-player and launch paths and the per-run budgets' reporter.
 */
final class WorldActions {

    private WorldActions() {}

    static List<ActionType<?>> all(ItemCatalog items, WorldVerbs world) {
        return List.of(
                new HealAction(),
                new DamageAction(),
                new VelocityAction(),
                new ParticleAction(world),
                new LaunchAction(world),
                new BreakBlocksAction(world),
                new SetBlockAction(world),
                new DropItemAction(items, world),
                new StackEditAction());
    }
}
