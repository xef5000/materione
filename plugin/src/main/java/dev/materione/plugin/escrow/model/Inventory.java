package dev.materione.plugin.escrow.model;

/**
 * The one side of a transfer the model does not own: the player's live inventory (gui-input-durability.md I1, I2: a
 * stack leaves or enters the inventory in the same step as the account changes). The runtime (row P) implements it
 * over Bukkit's inventory; the model's tests implement it over a list with a capacity.
 *
 * <p>There is deliberately no "drop at the feet" method: the model has no way to write to a world (G4). What does not
 * fit stays OWED in the account.
 */
public interface Inventory {

    /** Removes one stack equal to {@code stack}; false (and no change) when the inventory holds none. */
    boolean remove(StackBytes stack);

    /** Adds the whole stack when it fits; false (and no change) when it does not. */
    boolean tryAdd(StackBytes stack);
}
