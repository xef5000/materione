/**
 * The IO half of the escrow ledger (gui-input-durability.md §5.6; #247 row L): the per-player FIFO writer, preload,
 * compaction and quarantine of {@code storage/escrow/<uuid>.ledger}, on the plugin's IO executor.
 *
 * <p>This is the only escrow package allowed to touch files, and the only one that may block an executor thread;
 * it may not depend on Bukkit or Paper (no entity, inventory or world) so it cannot do owner-thread work.
 * {@code EscrowIoBansTest} enforces both halves of that.
 */
package dev.materione.plugin.escrow.io;
