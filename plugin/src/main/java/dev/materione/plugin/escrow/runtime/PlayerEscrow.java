package dev.materione.plugin.escrow.runtime;

import java.util.function.Consumer;

/**
 * One online player's escrow state: the recovery gate, the live account, and what writes the account into the
 * player's persistent data (gui-input-durability.md §5.7, I1). The account here always equals the bytes last written,
 * because {@link #commit} does both, so the live persistent value matches the live inventory (§5.4).
 *
 * <p>Owner-thread only: it is read and changed inside a tier-1 step on the player's own thread. The registry that
 * holds one per player is concurrent; this object is not.
 */
public final class PlayerEscrow extends CustodyAccount {

    /** The recovery gate (§5.7). */
    /** @param persist writes the encoded account into the player's persistent data (no IO: memory only) */
    public PlayerEscrow(Consumer<byte[]> persist) {
        super(persist);
    }
}
