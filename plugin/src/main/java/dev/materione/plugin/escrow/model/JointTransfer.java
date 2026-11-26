package dev.materione.plugin.escrow.model;

import java.util.List;
import java.util.Optional;

/** Pure owner/account transitions of the joint two-holder hand-off. */
public final class JointTransfer {

    private JointTransfer() {}

    /** Freezes the source entry before any joint decision exists. */
    public static Step prepareSource(Account account, Id128 entryId, Id128 txn) {
        Optional<Entry> found = account.find(entryId);
        if (found.isEmpty()) {
            return new Step.Refused(Refusal.STALE);
        }
        Entry source = found.get();
        if (source.state() == EntryState.EXITING || source.state() == EntryState.RESERVED
                || source.state() == EntryState.TRANSFERRING) {
            return new Step.Refused(Refusal.BUSY);
        }
        if (source.state() != EntryState.HELD) {
            return new Step.Refused(Refusal.STALE);
        }
        return EscrowTransfer.finish(account.replaced(source.transferring(txn)),
                List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Adds an invisible destination reservation. The item is not owned here until the shared INTENT is durable.
     */
    public static Step reserve(Account destination, Entry source, Id128 reservation, Id128 txn,
            String originGui, int slotHint) {
        if (slotHint < 0 || slotHint > Entry.MAX_SLOT_HINT
                || destination.find(reservation).isPresent()
                || destination.entries().stream().anyMatch(entry -> entry.slotHint() == slotHint)) {
            return new Step.Refused(Refusal.INVALID);
        }
        Entry reserved = new Entry(reservation, EntryState.RESERVED, txn, source.sinceMillis(), originGui,
                slotHint, source.stack());
        return EscrowTransfer.finish(destination.added(reserved), List.of(), List.of(), List.of(), List.of());
    }

    /** Removes the source only after the joint decision is durable. Idempotent after a prior recovery. */
    public static Step commitSource(Account source, Ledger decisions, Id128 txn, Id128 entryId) {
        if (!decisions.isTransferDecided(txn)) {
            return new Step.Refused(Refusal.UNDECIDED);
        }
        Optional<Entry> found = source.find(entryId);
        if (found.isEmpty()) {
            return new Step.NoOp(source);
        }
        if (found.get().state() != EntryState.TRANSFERRING || !found.get().txn().equals(txn)) {
            return new Step.Refused(Refusal.STALE);
        }
        return EscrowTransfer.shrunk(source.removed(entryId), List.of(), List.of(), List.of(),
                List.of(found.get().stack()));
    }

    /** Promotes the destination reservation after the same joint decision. Repeating it is a no-op. */
    public static Step commitDestination(Account destination, Ledger decisions, Id128 txn, Id128 reservation,
            boolean held) {
        if (!decisions.isTransferDecided(txn)) {
            return new Step.Refused(Refusal.UNDECIDED);
        }
        Optional<Entry> found = destination.find(reservation);
        if (found.isEmpty()) {
            return new Step.NoOp(destination);
        }
        Entry entry = found.get();
        if (entry.state() != EntryState.RESERVED || !entry.txn().equals(txn)) {
            if (!entry.txn().isZero() || entry.id().equals(reservation)
                    && entry.state() != EntryState.RESERVED && entry.state() != EntryState.EXITING) {
                return new Step.NoOp(destination);
            }
            return new Step.Refused(Refusal.STALE);
        }
        return EscrowTransfer.shrunk(destination.replaced(entry.committed(held)),
                List.of(), List.of(), List.of(), List.of());
    }

    /** Pre-decision recovery returns the source item through the normal inventory/OWED path. */
    public static Step rollbackSource(Account account, TransferHolder holder, Inventory inventory, Id128 txn) {
        Optional<Entry> entry = account.findByTxn(txn);
        if (entry.isEmpty()) {
            return new Step.NoOp(account);
        }
        if (entry.get().state() != EntryState.TRANSFERRING) {
            return new Step.NoOp(account);
        }
        return holder.kind() == TransferHolder.Kind.KEYED
                ? EscrowTransfer.shrunk(account.replaced(entry.get().held()),
                        List.of(), List.of(), List.of(), List.of())
                : EscrowTransfer.returnFrozen(account, inventory, entry.get());
    }

    /** Pre-decision recovery discards a reservation without making it visible. */
    public static Step rollbackDestination(Account account, Id128 txn) {
        Optional<Entry> entry = account.findByTxn(txn);
        if (entry.isEmpty() || entry.get().state() != EntryState.RESERVED) {
            return new Step.NoOp(account);
        }
        return EscrowTransfer.shrunk(account.removed(entry.get().id()),
                List.of(), List.of(), List.of(), List.of());
    }

    /** Idempotent recovery of both transactional states when an account is read. */
    public static Account recovered(Account account, Ledger decisions, TransferHolder holder, Inventory inventory) {
        Account next = account;
        for (LedgerRecord record : decisions.records()) {
            if (!(record instanceof LedgerRecord.TransferIntent intent)) {
                continue;
            }
            if (intent.source().equals(holder)) {
                Entry found = next.find(intent.sourceEntry()).orElse(null);
                if (found != null && (Long.compareUnsigned(next.gen(), intent.sourceGen()) < 0
                        || found.txn().equals(intent.txn()) && found.state() == EntryState.TRANSFERRING
                        || found.state() == EntryState.HELD && stackMatches(found, intent))) {
                    next = new Account(next.gen() + 1, next.entries().stream()
                            .filter(entry -> !entry.id().equals(intent.sourceEntry())).toList());
                }
            }
            if (intent.destination().equals(holder)) {
                Entry reservation = next.find(intent.destinationReservation()).orElse(null);
                if (reservation != null) {
                    if (reservation.state() == EntryState.RESERVED && reservation.txn().equals(intent.txn())) {
                        next = new Account(next.gen() + 1, next.entries().stream()
                                .map(entry -> entry.id().equals(reservation.id())
                                        ? entry.committed(true) : entry).toList());
                    }
                } else if (Long.compareUnsigned(next.gen(), intent.destinationGen()) < 0
                        && intent.stack() != null
                        && next.entries().stream().noneMatch(
                                entry -> entry.slotHint() == intent.destinationSlot())) {
                    Entry restored = Entry.held(intent.destinationReservation(), intent.stack(),
                            "custody.transfer", intent.destinationSlot(), intent.atMillis());
                    next = new Account(next.gen() + 1, appended(next.entries(), restored));
                }
            }
        }
        List<Entry> transactional = account.entries().stream()
                .filter(entry -> entry.state() == EntryState.TRANSFERRING || entry.state() == EntryState.RESERVED)
                .toList();
        for (Entry entry : transactional) {
            var intent = decisions.transfer(entry.txn());
            Step step;
            if (entry.state() == EntryState.TRANSFERRING) {
                step = intent.filter(value -> value.source().equals(holder)).isPresent()
                        ? commitSource(next, decisions, entry.txn(), entry.id())
                        : rollbackSource(next, holder, inventory, entry.txn());
            } else {
                step = intent.filter(value -> value.destination().equals(holder)).isPresent()
                        ? commitDestination(next, decisions, entry.txn(), entry.id(),
                                holder.kind() == TransferHolder.Kind.KEYED)
                        : rollbackDestination(next, entry.txn());
            }
            if (step instanceof Step.Applied applied) {
                next = applied.account();
            }
        }
        return next;
    }

    private static boolean stackMatches(Entry entry, LedgerRecord.TransferIntent intent) {
        return entry.stack().hash16().equals(intent.stackHash());
    }

    private static List<Entry> appended(List<Entry> entries, Entry entry) {
        var next = new java.util.ArrayList<>(entries);
        next.add(entry);
        return next;
    }
}
