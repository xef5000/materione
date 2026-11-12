package dev.materione.plugin.escrow.runtime;

import java.util.UUID;

/**
 * One online player as the tier-2 and tier-3 runtime needs them (gui-input-durability.md §5.6): a thread to run owner
 * steps on, the live state those steps change, and the platform's own save. An interface so {@link SaveBarrier} and
 * {@link ExitRunner} are driven without a server (the rig runs a manual queue and a scripted disk); the Bukkit
 * implementation is {@link PlayerOwner}.
 *
 * @param <T> one stack
 */
public interface Owner<T> {

    /** The player's id (the ledger's and the saved file's key). */
    UUID id();

    /** The player's live escrow: the account and the gate. Owner-thread only. */
    PlayerEscrow escrow();

    /** The player's live state for a step: the open window's slots (if any), the cursor and the inventory. */
    Live<T> live();

    /**
     * Runs {@code task} on the player's own thread at its next opportunity (one {@code executeEntity} task: on Folia,
     * the thread of the region that owns the player, §5.6). When the player is removed before it can run,
     * {@code retired} (possibly null) runs instead.
     */
    void run(Runnable task, Runnable retired);

    /** As {@link #run}, after at least {@code millis} milliseconds. */
    void runAfter(long millis, Runnable task, Runnable retired);

    /**
     * The platform's own save of this player: {@code Player.saveData()}, synchronous, on the calling thread, which must
     * be the player's own. The one synchronous IO design §5.4 admits (the owner-approved D8 exception), so
     * {@link SaveBarrier} is the only caller (a build rule) and calls it only after its rate limit allowed it.
     */
    void save();
}
