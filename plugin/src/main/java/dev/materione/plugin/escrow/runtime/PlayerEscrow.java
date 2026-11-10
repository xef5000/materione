package dev.materione.plugin.escrow.runtime;

import dev.materione.plugin.escrow.model.Account;
import dev.materione.plugin.escrow.model.Id128;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One online player's escrow state: the recovery gate, the live account, and what writes the account into the
 * player's persistent data (gui-input-durability.md §5.7, I1). The account here always equals the bytes last written,
 * because {@link #commit} does both, so the live persistent value matches the live inventory (§5.4).
 *
 * <p>Owner-thread only: it is read and changed inside a tier-1 step on the player's own thread. The registry that
 * holds one per player is concurrent; this object is not.
 */
public final class PlayerEscrow {

    /** The recovery gate (§5.7). */
    public enum Gate {
        /** Joined, recovery has not completed (it needs the pre-login preload): strict transfers are refused. */
        CLOSED,
        /** Recovery ran: strict transfers are served. */
        OPEN,
        /** The account or the ledger is unreadable: strict transfers are refused until an admin resolves it (K18). */
        UNRECOVERABLE
    }

    private final Consumer<byte[]> persist;
    private Gate gate = Gate.CLOSED;
    private Account account = Account.EMPTY;
    private String why = "";
    /** Entries whose stack is between two windows (a re-open): HELD, in no slot, and not for a close to return. */
    private final Set<Id128> carrying = new HashSet<>();

    /** @param persist writes the encoded account into the player's persistent data (no IO: memory only) */
    public PlayerEscrow(Consumer<byte[]> persist) {
        this.persist = Objects.requireNonNull(persist, "persist");
    }

    public Gate gate() {
        return gate;
    }

    /** Whether strict transfers are served for this player. */
    public boolean open() {
        return gate == Gate.OPEN;
    }

    /** The live account (empty before recovery). */
    public Account account() {
        return account;
    }

    /** Why the gate is {@link Gate#UNRECOVERABLE}; empty otherwise. */
    public String why() {
        return why;
    }

    /** Opens the gate with the recovered account. */
    void open(Account recovered) {
        this.account = Objects.requireNonNull(recovered, "recovered");
        this.gate = Gate.OPEN;
        this.why = "";
    }

    /** Marks the player unrecoverable; the account bytes are left exactly as they are. */
    void unrecoverable(String reason) {
        this.gate = Gate.UNRECOVERABLE;
        this.why = reason;
    }

    /**
     * The one write: the persistent value becomes {@code encoded} and the live account becomes {@code next}, together
     * (§5.4: the account is encoded eagerly in the step, never lazily at the next tick).
     */
    void commit(Account next, byte[] encoded) {
        persist.accept(encoded);
        this.account = next;
    }

    /** Writes the live account's bytes again without changing it (a respawned player's data). */
    void persistAgain(byte[] encoded) {
        persist.accept(encoded);
    }

    Set<Id128> carrying() {
        return carrying;
    }
}
