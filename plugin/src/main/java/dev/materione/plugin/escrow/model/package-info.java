/**
 * The pure escrow model of crash-safe GUI input slots (gui-input-durability.md; #247 row M): the account that
 * lives in the player's snapshot (tier 1), the exit-decision ledger records (tier 2), their byte-exact
 * versioned codecs (§5.3) and recovery as a pure function (§5.7).
 *
 * <p>Nothing here touches the world, a file, a thread or a Bukkit type: stacks are opaque bytes
 * ({@link dev.materione.plugin.escrow.model.StackBytes}), the player's inventory is the two-method
 * {@link dev.materione.plugin.escrow.model.Inventory} port, time and ids are passed in. {@code EscrowModelBansTest}
 * fails the build if that stops being true. Rows L (ledger IO) and P (runtime) build on this package.
 */
package dev.materione.plugin.escrow.model;
