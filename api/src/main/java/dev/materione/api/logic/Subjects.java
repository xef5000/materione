package dev.materione.api.logic;

import java.util.Optional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Who a targetable action acts on (logic.md §39). An action that declares
 * {@link ActionDescription#targetable() targetable} reads its subject here, at the one place it touches the player
 * or the entity, instead of reading {@link Facets#PLAYER}: without a {@code target:} the subject is the run's own
 * player (the action behaves exactly as it did before targets existed), and with one it is the entity the engine
 * resolved and dispatched the step to.
 *
 * <p>Everything else an action reads from the context (expressions, {@code player.*}, placeholders, the run's own
 * variables) stays the run's own: a message "to the other player" still reads {@code ${player.name}} as the sender.
 * Only the effect moves.
 */
public final class Subjects {

    private Subjects() {}

    /**
     * The player the action acts on: the target when it is a player, empty when it is not (a player-only action
     * given a zombie; the engine refuses that before the action runs, so this is a guard), else the run's player.
     */
    public static Optional<Player> player(LogicContext context) {
        Optional<ResolvedTarget> target = context.facet(ResolvedTarget.FACET);
        if (target.isPresent()) {
            return target.get().anchor() instanceof Player player ? Optional.of(player) : Optional.empty();
        }
        return context.facet(Facets.PLAYER);
    }

    /** The entity the action acts on: the target, else the run's player. */
    public static Optional<Entity> entity(LogicContext context) {
        Optional<ResolvedTarget> target = context.facet(ResolvedTarget.FACET);
        if (target.isPresent()) {
            return target.get().anchor() instanceof Entity entity ? Optional.of(entity) : Optional.empty();
        }
        return context.facet(Facets.PLAYER).map(player -> player);
    }

    /** Whether the action was given a target other than the run's own player. */
    public static boolean targeted(LogicContext context) {
        return context.has(ResolvedTarget.FACET);
    }
}
