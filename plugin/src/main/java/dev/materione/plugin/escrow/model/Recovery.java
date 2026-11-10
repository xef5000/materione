package dev.materione.plugin.escrow.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Recovery (gui-input-durability.md §5.7): a pure function of the saved account, the ledger and the player's
 * inventory (I6). It runs when the player joins, before any interaction, and only mutates live memory (the
 * inventory port and the account it returns), so a crash during recovery leaves the disk as it was and the next
 * boot computes the same result (K15).
 *
 * <pre>
 * for each entry e of the saved account, in order:
 *     the ledger holds an INTENT for e.id  -> dropped            (G2: the decision is durable, any state, any age)
 *     else                                 -> returned if it fits, otherwise OWED   (G1: HELD, OWED, or EXITING without INTENT)
 * </pre>
 *
 * <p>An INTENT for an entry the saved account does not hold is the one rollback case (G3): the snapshot predates the
 * move-in, the stack is still in the saved inventory, and recovery does nothing to it.
 *
 * <p>Idempotence (I6, K16): the result's account holds only OWED entries, so running recovery again on it, with the
 * same ledger, returns what now fits and drops nothing; running it on a drained account does nothing and spends no
 * generation.
 *
 * <p>An unreadable account or ledger is {@link Result.Unrecoverable}: nothing is returned or deleted, because
 * guessing is the one way to lose items (K18).
 */
public final class Recovery {

    private Recovery() {}

    /** What recovery did. */
    public sealed interface Result {

        /**
         * The account was readable and the ledger valid.
         *
         * @param account  the account after recovery: only OWED entries remain ({@code gen} advanced once when
         *                 anything changed, otherwise it is the input account)
         * @param encoded  the new persistent value; empty when nothing changed (nothing to write)
         * @param returned stacks added to the inventory, in account order
         * @param owed     stacks still OWED because they did not fit (reported once per join, {@code MTR-0103})
         * @param dropped  entries deleted because their INTENT is durable (for the audit line)
         */
        record Recovered(
                Account account,
                Optional<byte[]> encoded,
                List<StackBytes> returned,
                List<StackBytes> owed,
                List<Entry> dropped)
                implements Result {

            public Recovered {
                returned = List.copyOf(returned);
                owed = List.copyOf(owed);
                dropped = List.copyOf(dropped);
            }
        }

        /**
         * The player's escrow state is unreadable; strict transfers are refused for the player until an admin
         * resolves it by hand ({@code MTR-0101} later). Nothing was touched.
         *
         * @param cause which half
         * @param detail the account's {@link AccountRead.Reason} name, or the ledger's cause
         */
        record Unrecoverable(Cause cause, String detail) implements Result {}
    }

    /** Which half of the escrow state is unreadable. */
    public enum Cause {
        /** The player's {@code materione:gui_escrow} value. */
        ACCOUNT,
        /** The player's ledger file. */
        LEDGER
    }

    /** Recovery of the player on join. */
    public static Result recover(AccountRead saved, LedgerRead ledgerRead, Inventory inventory) {
        if (saved instanceof AccountRead.Unreadable unreadable) {
            return new Result.Unrecoverable(Cause.ACCOUNT, unreadable.reason().name());
        }
        Ledger ledger;
        switch (ledgerRead) {
            case LedgerRead.Valid valid -> ledger = valid.ledger();
            case LedgerRead.Corrupt corrupt -> {
                return new Result.Unrecoverable(Cause.LEDGER, "corrupt at " + corrupt.offset() + ": " + corrupt.why());
            }
            case LedgerRead.Newer newer -> {
                return new Result.Unrecoverable(Cause.LEDGER, "newer version " + newer.version());
            }
        }
        Account account = saved instanceof AccountRead.Readable readable ? readable.account() : Account.EMPTY;
        return recover(account, ledger, inventory);
    }

    /** Recovery of a readable account against a valid ledger. */
    public static Result recover(Account account, Ledger ledger, Inventory inventory) {
        if (account.isEmpty()) {
            return new Result.Recovered(account, Optional.empty(), List.of(), List.of(), List.of());
        }
        List<StackBytes> returned = new ArrayList<>();
        List<StackBytes> owed = new ArrayList<>();
        List<Entry> dropped = new ArrayList<>();
        List<Entry> retained = new ArrayList<>();
        for (Entry entry : account.entries()) {
            if (ledger.hasIntent(entry.id())) {
                dropped.add(entry);
            } else if (inventory.tryAdd(entry.stack())) {
                returned.add(entry.stack());
            } else {
                owed.add(entry.stack());
                retained.add(entry.state() == EntryState.OWED ? entry : entry.owed());
            }
        }
        boolean unchanged = dropped.isEmpty() && returned.isEmpty() && retained.equals(account.entries());
        if (unchanged) {
            return new Result.Recovered(account, Optional.empty(), List.of(), owed, List.of());
        }
        Account next = account.next(retained);
        return new Result.Recovered(next, Optional.of(AccountCodec.encodeUnchecked(next)), returned, owed, dropped);
    }
}
