package dev.materione.plugin.logic.variable;

import dev.materione.api.logic.variable.StackRef;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds the stack a player holds, for the readers of item variables that are not a run: {@code /materione var} and
 * {@code api.logic().variables()} (logic.md §38). A run is given its stack by its trigger and does not use this.
 */
@FunctionalInterface
public interface HeldStacks {

    /** No player holds anything: every read is the default, every write is refused. */
    HeldStacks NONE = player -> Optional.empty();

    /**
     * The stack in the player's main hand, or empty when the player is offline, the hand is empty, or the player
     * is not on this thread.
     *
     * @throws dev.materione.api.logic.ReadUnavailableException when the player is online but owned by another
     *         thread, so a caller can tell "unavailable" from "holds nothing"
     */
    Optional<StackRef> mainHand(UUID player);
}
