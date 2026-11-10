package dev.materione.plugin.escrow.model;

import java.util.List;
import java.util.Optional;

/**
 * Tier 2 (gui-input-durability.md §5.4): an exit with a durable decision (consume today; hand-off later). The steps
 * of the protocol, as pure functions; the ledger append between steps 1 and 3 is row L's IO and appears here only as
 * the {@link Ledger} the caller passes in (the in-memory image of what the file durably holds).
 *
 * <pre>
 * 1  owner:  {@link #beginExit} / {@link #freeze}   the stack becomes EXITING(T) in the account, source removed
 * 2  async:  append {@link #intent} to the ledger   the decision point is the CRC-valid INTENT
 * 3  owner:  {@link #complete}                      on ack: delete E (only if the INTENT is durable)
 *            {@link #abortUndecided}                the append failed (MTR-0102): E goes back, only while undecided
 * 4  async:  append {@link #acknowledge}            once the barrier acknowledged a snapshot with gen &gt;= g(E)
 * </pre>
 *
 * <p>There is deliberately no abort after the decision: an abort after a durable INTENT is exactly the duplicate or
 * loss the ledger exists to prevent (§5.4). {@link #abortUndecided} refuses once the INTENT is durable.
 */
public final class Exits {

    private Exits() {}

    /**
     * Step 1 for an incoming stack ({@code #248}'s consume-incoming): the stack leaves the inventory and enters the
     * account as the EXITING {@code entry}. Nothing is removed from the source unless every check passed: that is
     * the caller's validation before this call; this call is the removal.
     */
    public static Step beginExit(Account account, Inventory inventory, Entry entry) {
        if (entry.state() != EntryState.EXITING) {
            return EscrowTransfer.refused(Refusal.INVALID);
        }
        if (account.entries().stream().anyMatch(existing -> existing.txn().equals(entry.txn()))) {
            return EscrowTransfer.refused(Refusal.INVALID);
        }
        return EscrowTransfer.moveIn(account, inventory, entry);
    }

    /** Step 1 for a stack already in a slot (HELD): it freezes as EXITING(txn); clicks on it are refused (busy). */
    public static Step freeze(Account account, Id128 entryId, Id128 txn) {
        if (txn.isZero() || account.findByTxn(txn).isPresent()) {
            return EscrowTransfer.refused(Refusal.INVALID);
        }
        Optional<Entry> found = account.find(entryId);
        if (found.isEmpty()) {
            return EscrowTransfer.refused(Refusal.STALE);
        }
        Entry entry = found.get();
        if (entry.state() == EntryState.EXITING) {
            return EscrowTransfer.refused(Refusal.BUSY);
        }
        if (entry.state() != EntryState.HELD) {
            return EscrowTransfer.refused(Refusal.STALE);
        }
        return EscrowTransfer.shrunk(account.replaced(entry.exiting(txn)), List.of(), List.of(), List.of(), List.of());
    }

    /** The INTENT record step 2 appends for an EXITING entry. */
    public static LedgerRecord.Intent intent(Entry exiting, ExitKind kind, long amount, long atMillis) {
        if (exiting.state() != EntryState.EXITING) {
            throw new IllegalArgumentException("only an EXITING entry has an exit to record");
        }
        return new LedgerRecord.Intent(exiting.txn(), exiting.id(), kind, exiting.stack().hash16(), amount, atMillis);
    }

    /**
     * Step 3, on the append's acknowledgment: the entry of {@code txn} is deleted ({@link Step.Applied#consumed()}).
     * The decision must be durable ({@link Refusal#UNDECIDED} otherwise: the live path never skips the ledger). If
     * the entry is gone (a recovery or a second apply got there first) nothing happens ({@link Step.NoOp}).
     */
    public static Step complete(Account account, Ledger ledger, Id128 txn) {
        if (!ledger.isDecided(txn)) {
            return EscrowTransfer.refused(Refusal.UNDECIDED);
        }
        Optional<Entry> found = account.findByTxn(txn);
        if (found.isEmpty()) {
            return new Step.NoOp(account);
        }
        return EscrowTransfer.shrunk(account.removed(found.get().id()), List.of(), List.of(), List.of(),
                List.of(found.get().stack()));
    }

    /**
     * The append of INTENT failed (disk full, permissions; {@code MTR-0102} later): while no INTENT is durable the
     * exit is not decided, so the frozen stack goes back to the inventory (or OWED). Once decided there is no abort
     * ({@link Refusal#DECIDED}).
     */
    public static Step abortUndecided(Account account, Ledger ledger, Inventory inventory, Id128 txn) {
        Optional<Entry> found = account.findByTxn(txn);
        if (ledger.isDecided(txn)) {
            return EscrowTransfer.refused(Refusal.DECIDED);
        }
        if (found.isEmpty()) {
            return new Step.NoOp(account);
        }
        return EscrowTransfer.returnFrozen(account, inventory, found.get());
    }

    /**
     * Step 4: the DONE record, or empty when the exit is not (yet) acknowledgeable. It is only when the INTENT is
     * durable, no DONE exists yet, and a saved snapshot with {@code savedGen >= exitGen} is known (unsigned order),
     * where {@code exitGen} is the {@code gen} of the step-1 account. "Not yet acknowledged" is never read as
     * acknowledged (K23).
     */
    public static Optional<LedgerRecord.Done> acknowledge(Ledger ledger, Id128 txn, long exitGen, long savedGen) {
        if (!ledger.isDecided(txn) || ledger.isDone(txn) || Long.compareUnsigned(savedGen, exitGen) < 0) {
            return Optional.empty();
        }
        return Optional.of(new LedgerRecord.Done(txn));
    }
}
