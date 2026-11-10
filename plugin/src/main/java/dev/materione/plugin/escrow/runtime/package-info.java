/**
 * The owner-side half of the escrow (gui-input-durability.md §5.6): platform glue that may use Bukkit but never
 * touches a file and never blocks on a future. Row L puts the pre-login and quit listener here; row P adds the
 * tier-1 runtime, the recovery gate and the death release beside it. {@code EscrowIoBansTest} bans
 * {@code java.io}, {@code java.nio.file}, {@code java.nio.channels} and blocking waits in this package.
 */
package dev.materione.plugin.escrow.runtime;
