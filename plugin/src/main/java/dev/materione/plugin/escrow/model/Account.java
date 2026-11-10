package dev.materione.plugin.escrow.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * The player's escrow account (gui-input-durability.md §5.1): the entries and the generation. Immutable; every
 * mutation returns the next account with {@code gen + 1}. The generation is not a clock, it orders the snapshots
 * of one player (the tier-3 barrier compares it against what a saved file holds).
 *
 * <p>Entry order is insertion order and is preserved by replacement, so a given history always encodes to the
 * same bytes.
 *
 * @param gen     the generation (a {@code u64}; the sign bit is just a bit)
 * @param entries the entries, no two with the same id
 */
public record Account(long gen, List<Entry> entries) {

    /** The account of a player who never escrowed anything. */
    public static final Account EMPTY = new Account(0L, List.of());

    /** @throws IllegalArgumentException on a duplicate entry id */
    public Account {
        entries = List.copyOf(entries);
        var seen = new HashSet<Id128>();
        for (Entry entry : entries) {
            if (!seen.add(entry.id())) {
                throw new IllegalArgumentException("duplicate entry id " + entry.id());
            }
        }
    }

    /** The entry with this id. */
    public Optional<Entry> find(Id128 id) {
        for (Entry entry : entries) {
            if (entry.id().equals(id)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** The EXITING entry of this transaction. */
    public Optional<Entry> findByTxn(Id128 txn) {
        for (Entry entry : entries) {
            if (entry.state() == EntryState.EXITING && entry.txn().equals(txn)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** True when no entry is escrowed. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The next generation with exactly these entries. */
    Account next(List<Entry> next) {
        return new Account(gen + 1, next);
    }

    /** The next generation with {@code entry} appended. */
    Account added(Entry entry) {
        var next = new ArrayList<>(entries);
        next.add(entry);
        return next(next);
    }

    /** The next generation with the entry of the same id replaced in place. */
    Account replaced(Entry entry) {
        var next = new ArrayList<Entry>(entries.size());
        for (Entry existing : entries) {
            next.add(existing.id().equals(entry.id()) ? entry : existing);
        }
        return next(next);
    }

    /** The next generation without the entry of this id. */
    Account removed(Id128 id) {
        var next = new ArrayList<Entry>(entries.size());
        for (Entry existing : entries) {
            if (!existing.id().equals(id)) {
                next.add(existing);
            }
        }
        return next(next);
    }
}
