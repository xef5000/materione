package dev.materione.plugin.escrow.io;

import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The barrier's way to read a player's saved file back (gui-input-durability.md §5.4 tier 3): off every owner thread,
 * the result completed on an IO thread, never awaited with {@code get()} or {@code join()}. An interface so the
 * barrier is tested against a scripted disk as well as the real files ({@link EscrowReadBack}).
 */
public interface SnapshotReader {

    /**
     * Reads the player's file once, with the retries that tolerate the server replacing it mid-read (F2). Never fails:
     * a problem is {@link ReadBack.Unavailable}. May be called from any thread; it does no IO on the caller's.
     */
    CompletableFuture<ReadBack> read(UUID player);

    /**
     * Polls the player's file until it holds an account whose {@code gen} is at least {@code minGen} (unsigned), for a
     * player who left before the barrier acknowledged their exit (the quit save is still landing): the {@code gen}
     * reached, or empty when it never was within the polling window. Never fails.
     */
    CompletableFuture<OptionalLong> awaitAtLeast(UUID player, long minGen);
}
