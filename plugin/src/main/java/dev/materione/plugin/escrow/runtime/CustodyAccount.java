package dev.materione.plugin.escrow.runtime;

import dev.materione.plugin.escrow.gate.CrashGate;
import dev.materione.plugin.escrow.model.Account;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.AccountCodec;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The in-memory, owner-thread half shared by every custody account. The persistence consumer is PDC (player or
 * stack) and changes bytes in the same step as the surrounding live state.
 */
public abstract class CustodyAccount {

    /** The account's recovery gate. */
    public enum Gate {
        CLOSED,
        OPEN,
        UNRECOVERABLE
    }

    private final Consumer<byte[]> persist;
    private Gate gate = Gate.CLOSED;
    private Account account = Account.EMPTY;
    private String why = "";
    private final Set<Id128> carrying = new HashSet<>();

    protected CustodyAccount(Consumer<byte[]> persist) {
        this.persist = Objects.requireNonNull(persist, "persist");
    }

    public Gate gate() {
        return gate;
    }

    public boolean open() {
        return gate == Gate.OPEN;
    }

    public Account account() {
        return account;
    }

    public String why() {
        return why;
    }

    public void open(Account recovered) {
        account = Objects.requireNonNull(recovered, "recovered");
        gate = Gate.OPEN;
        why = "";
    }

    public void unrecoverable(String reason) {
        gate = Gate.UNRECOVERABLE;
        why = reason;
    }

    /** Commits the bytes and in-memory image together on the holder owner's thread. */
    public boolean commit(Account next, byte[] encoded) {
        try {
            persist.accept(encoded);
        } catch (RuntimeException failed) {
            return false;
        }
        account = next;
        CrashGate.at(CrashGate.Gates.ACCOUNT_WRITTEN);
        return true;
    }

    /**
     * Advances the player's default account generation without changing entries. A holder-transfer uses this as a
     * snapshot marker when the source/destination PDC keys are distinct: one saved player compound proves them all.
     */
    public boolean touch() {
        Account next = new Account(account.gen() + 1, account.entries());
        return commit(next, AccountCodec.encode(next));
    }

    public void persistAgain(byte[] encoded) {
        persist.accept(encoded);
    }

    public Set<Id128> carrying() {
        return carrying;
    }
}
