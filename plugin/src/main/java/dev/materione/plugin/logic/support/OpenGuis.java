package dev.materione.plugin.logic.support;

import dev.materione.api.NamespacedId;
import java.util.Optional;
import org.bukkit.entity.Player;

/**
 * The port {@code in_gui} reads (logic.md §22): which Materione GUI a player has open, by the GUI's id.
 * {@code BuiltinLogic} backs it with the GUI session runtime (logic.md §6) through the {@code GuiBridge};
 * {@link #none()} answers "no GUI", for tests and hosts without that runtime, so {@code in_gui} is false
 * and never throws.
 *
 * <p>A condition runs on the player's thread (logic.md §16 decision 17), so an implementation may read the
 * session it keeps for that player without a hop.
 */
@FunctionalInterface
public interface OpenGuis {

    /** The id of the GUI {@code player} has open, or empty when none (or when this port cannot tell). */
    Optional<NamespacedId> openGui(Player player);

    /** The port with no GUI runtime behind it: nothing is ever open. */
    static OpenGuis none() {
        return player -> Optional.empty();
    }
}
