package dev.materione.plugin.logic.support;

import dev.materione.plugin.view.ViewRef;
import java.util.List;
import org.bukkit.entity.Player;

/**
 * The port {@code in_view} reads (logic.md §22): which views a player has open, by kind and definition.
 * {@code BuiltinLogic} backs it with the view registry through the {@code GuiBridge}; {@link #none()} answers
 * "nothing open", for tests and hosts without that runtime, so {@code in_view} is false and never throws.
 *
 * <p>A condition runs on the player's thread (logic.md §16 decision 17), so an implementation may read the
 * records it keeps for that player without a hop.
 */
@FunctionalInterface
public interface OpenViews {

    /** The views {@code player} has open now, one per kind at most; empty when none (or when this port cannot tell). */
    List<ViewRef> openViews(Player player);

    /** The port with no view runtime behind it: nothing is ever open. */
    static OpenViews none() {
        return player -> List.of();
    }
}
