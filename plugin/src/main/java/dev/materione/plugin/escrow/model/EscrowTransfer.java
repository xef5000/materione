package dev.materione.plugin.escrow.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tier 1 (gui-input-durability.md §5.4): an ownership transfer inside the player's snapshot. Each method is one
 * owner-thread step that takes <em>both</em> sides, the account and the {@link Inventory} (I1, I2: this is the one
 * API through which a stack changes owner), and returns the next account with its encoded bytes.
 *
 * <p>Atomicity of the model: a refusal changes neither side. A step that can grow the account computes and encodes
 * the next account <em>before</em> the inventory is touched, so a cap failure cancels it cleanly (I7); a step that
 * touches the inventory first (a stack that fits) can only shrink or keep the account, which has no cap to fail. A
 * step advances {@code gen} once, however many entries it moves.
 *
 * <p>Pure: no clock (the {@link Entry} carries its {@code sinceMillis}), no random ids (the caller supplies them),
 * no IO. The cursor, the death drop list and the world are the caller's; the model only reports what it released.
 */
public final class EscrowTransfer {

    private EscrowTransfer() {}

    /**
     * Move-in (shift-click in, manual placement from the inventory): the stack leaves the inventory and enters the
     * account as {@code entry} (HELD, or EXITING for tier 2's incoming consume) in the same step.
     */
    public static Step moveIn(Account account, Inventory inventory, Entry entry) {
        if (account.find(entry.id()).isPresent() || entry.state() == EntryState.OWED) {
            return refused(Refusal.INVALID);
        }
        Account next = account.added(entry);
        Encoded encoded = encode(next);
        if (encoded.refusal != null) {
            return refused(encoded.refusal);
        }
        if (!inventory.remove(entry.stack())) {
            return refused(Refusal.NO_SUCH_STACK);
        }
        return applied(next, encoded.bytes, List.of(), List.of(), List.of(), List.of());
    }

    /** Placement from the cursor: a new HELD entry; the cursor was the source, so no inventory is touched. */
    public static Step place(Account account, Entry entry) {
        if (entry.state() != EntryState.HELD || account.find(entry.id()).isPresent()) {
            return refused(Refusal.INVALID);
        }
        return finish(account.added(entry), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Pick-up (slot to cursor): the entry leaves the account in the same step the stack becomes the player's cursor
     * stack, reported as {@code released}. From then on it is outside the guarantee (G5, K21).
     */
    public static Step pickUp(Account account, Id128 entryId) {
        Optional<Entry> found = account.find(entryId);
        if (found.isEmpty()) {
            return refused(Refusal.STALE);
        }
        Entry entry = found.get();
        if (entry.state() != EntryState.HELD) {
            return refused(entry.state() == EntryState.EXITING ? Refusal.BUSY : Refusal.STALE);
        }
        return shrunk(account.removed(entryId), List.of(entry.stack()), List.of(), List.of(), List.of());
    }

    /**
     * Swap of a slot with the cursor: the slot's stack leaves (released, to the cursor or a hotbar slot) and the new
     * HELD entry takes its place, in one step.
     */
    public static Step swap(Account account, Id128 slotEntryId, Entry replacement) {
        Optional<Entry> found = account.find(slotEntryId);
        if (found.isEmpty()) {
            return refused(Refusal.STALE);
        }
        Entry out = found.get();
        if (out.state() != EntryState.HELD) {
            return refused(out.state() == EntryState.EXITING ? Refusal.BUSY : Refusal.STALE);
        }
        if (replacement.state() != EntryState.HELD
                || (!replacement.id().equals(slotEntryId) && account.find(replacement.id()).isPresent())) {
            return refused(Refusal.INVALID);
        }
        List<Entry> next = new ArrayList<>();
        for (Entry existing : account.entries()) {
            next.add(existing.id().equals(slotEntryId) ? replacement : existing);
        }
        return finish(account.next(next), List.of(out.stack()), List.of(), List.of(), List.of());
    }

    /**
     * Move-out (an entry to the inventory: shift-click out, {@code gui.slot_item take}): into the inventory when it
     * fits, otherwise a HELD entry becomes OWED. Never a world drop (G4). An OWED entry that still does not fit is
     * refused ({@link Refusal#INVENTORY_FULL}) and nothing changes.
     */
    public static Step moveOut(Account account, Inventory inventory, Id128 entryId) {
        Optional<Entry> found = account.find(entryId);
        if (found.isEmpty()) {
            return refused(Refusal.STALE);
        }
        Entry entry = found.get();
        if (entry.state() == EntryState.EXITING) {
            return refused(Refusal.BUSY);
        }
        if (inventory.tryAdd(entry.stack())) {
            return shrunk(account.removed(entryId), List.of(), List.of(entry.stack()), List.of(), List.of());
        }
        if (entry.state() == EntryState.OWED) {
            return refused(Refusal.INVENTORY_FULL);
        }
        return shrunk(account.replaced(entry.owed()), List.of(), List.of(), List.of(entry.stack()), List.of());
    }

    /** An in-place edit of an escrowed stack ({@code set_amount}, repair, enchant): the entry's stack changes. */
    public static Step editStack(Account account, Id128 entryId, StackBytes edited) {
        Optional<Entry> found = account.find(entryId);
        if (found.isEmpty()) {
            return refused(Refusal.STALE);
        }
        if (found.get().state() != EntryState.HELD) {
            return refused(found.get().state() == EntryState.EXITING ? Refusal.BUSY : Refusal.STALE);
        }
        return finish(account.replaced(found.get().withStack(edited)), List.of(), List.of(), List.of(), List.of());
    }

    /** A reload or shape-changing re-open moved the entry to another slot (the entry keeps its id). */
    public static Step reslot(Account account, Id128 entryId, int slotHint) {
        Optional<Entry> found = account.find(entryId);
        if (found.isEmpty()) {
            return refused(Refusal.STALE);
        }
        if (slotHint < 0 || slotHint > Entry.MAX_SLOT_HINT) {
            return refused(Refusal.INVALID);
        }
        return shrunk(account.replaced(found.get().withSlotHint(slotHint)), List.of(), List.of(), List.of(),
                List.of());
    }

    /**
     * Close (player, flow, replaced window, quit, kick, plugin disable): every OWED and HELD entry goes to the
     * inventory, and what does not fit is OWED. EXITING entries stay frozen: a decision may be in flight (§5.5).
     */
    public static Step close(Account account, Inventory inventory) {
        return release(account, inventory, true);
    }

    /** {@code /materione claim}, join: only OWED entries try the inventory again; HELD entries stay in their slots. */
    public static Step claim(Account account, Inventory inventory) {
        return release(account, inventory, false);
    }

    /**
     * Death, in {@code PlayerDeathEvent} (§5.5, owner Q10). With {@code keepInventory} the entries return to the
     * inventory (what does not fit is OWED); otherwise their stacks are {@code released} for the caller to append to
     * the event's drop list. EXITING entries follow the ledger: deleted (consumed) if an INTENT exists, otherwise
     * treated like HELD. OWED entries are not in a window and stay owed.
     */
    public static Step death(Account account, Ledger ledger, Inventory inventory, boolean keepInventory) {
        List<StackBytes> released = new ArrayList<>();
        List<StackBytes> returned = new ArrayList<>();
        List<StackBytes> owed = new ArrayList<>();
        List<StackBytes> consumed = new ArrayList<>();
        List<Entry> next = new ArrayList<>();
        boolean changed = false;
        for (Entry entry : account.entries()) {
            if (entry.state() == EntryState.OWED) {
                next.add(entry);
                continue;
            }
            changed = true;
            if (entry.state() == EntryState.EXITING && ledger.hasIntent(entry.id())) {
                consumed.add(entry.stack());
            } else if (!keepInventory) {
                released.add(entry.stack());
            } else if (inventory.tryAdd(entry.stack())) {
                returned.add(entry.stack());
            } else {
                owed.add(entry.stack());
                next.add(entry.owed());
            }
        }
        if (!changed) {
            return new Step.NoOp(account);
        }
        return shrunk(account.next(next), released, returned, owed, consumed);
    }

    /**
     * Adopts the stacks of a legacy {@code gui_inputs} journal (a crash-era leftover, §10) as OWED entries, in one
     * step. The caller decodes the legacy bytes and builds the entries (OWED, fresh ids).
     */
    public static Step adoptLegacy(Account account, List<Entry> owedEntries) {
        if (owedEntries.isEmpty()) {
            return new Step.NoOp(account);
        }
        List<Entry> next = new ArrayList<>(account.entries());
        List<StackBytes> owed = new ArrayList<>();
        for (Entry entry : owedEntries) {
            boolean taken = next.stream().anyMatch(existing -> existing.id().equals(entry.id()));
            if (entry.state() != EntryState.OWED || taken) {
                return refused(Refusal.INVALID);
            }
            next.add(entry);
            owed.add(entry.stack());
        }
        return finish(account.next(next), List.of(), List.of(), owed, List.of());
    }

    // ---- shared with Exits and Recovery ------------------------------------------------------------------------

    /** An EXITING entry goes back to the player (an undecided exit aborted): the inventory, or OWED. One step. */
    static Step returnFrozen(Account account, Inventory inventory, Entry exiting) {
        if (inventory.tryAdd(exiting.stack())) {
            return shrunk(account.removed(exiting.id()), List.of(), List.of(exiting.stack()), List.of(), List.of());
        }
        return shrunk(account.replaced(exiting.owed()), List.of(), List.of(), List.of(exiting.stack()), List.of());
    }

    static Step.Refused refused(Refusal reason) {
        return new Step.Refused(reason);
    }

    static Step applied(
            Account next,
            byte[] encoded,
            List<StackBytes> released,
            List<StackBytes> returned,
            List<StackBytes> owed,
            List<StackBytes> consumed) {
        return new Step.Applied(next, encoded, released, returned, owed, consumed);
    }

    /** Encodes {@code next} under the caps and wraps it as an applied step, or refuses. For steps that touch no inventory. */
    static Step finish(
            Account next,
            List<StackBytes> released,
            List<StackBytes> returned,
            List<StackBytes> owed,
            List<StackBytes> consumed) {
        Encoded encoded = encode(next);
        if (encoded.refusal != null) {
            return refused(encoded.refusal);
        }
        return applied(next, encoded.bytes, released, returned, owed, consumed);
    }

    /**
     * As {@link #finish}, for a step whose account is no larger than before (move-out, pick-up, close, claim,
     * death, reslot): the caps are not re-checked, so releasing items can never be refused because an account is
     * over a cap it was not written under, and a step that already put stacks in the inventory has nothing left that
     * can fail.
     */
    static Step shrunk(
            Account next,
            List<StackBytes> released,
            List<StackBytes> returned,
            List<StackBytes> owed,
            List<StackBytes> consumed) {
        return applied(next, AccountCodec.encodeUnchecked(next), released, returned, owed, consumed);
    }

    /** Close and claim: OWED entries first (they are the oldest debt), then HELD ones when {@code includeHeld}. */
    private static Step release(Account account, Inventory inventory, boolean includeHeld) {
        Entry[] after = account.entries().toArray(new Entry[0]);
        boolean[] gone = new boolean[after.length];
        List<StackBytes> returned = new ArrayList<>();
        List<StackBytes> owed = new ArrayList<>();
        boolean changed = false;
        for (EntryState pass : new EntryState[] {EntryState.OWED, EntryState.HELD}) {
            if (pass == EntryState.HELD && !includeHeld) {
                break;
            }
            for (int i = 0; i < after.length; i++) {
                if (after[i].state() != pass) {
                    continue;
                }
                if (inventory.tryAdd(after[i].stack())) {
                    returned.add(after[i].stack());
                    gone[i] = true;
                    changed = true;
                } else if (pass == EntryState.HELD) {
                    owed.add(after[i].stack());
                    after[i] = after[i].owed();
                    changed = true;
                }
            }
        }
        if (!changed) {
            return new Step.NoOp(account);
        }
        List<Entry> next = new ArrayList<>();
        for (int i = 0; i < after.length; i++) {
            if (!gone[i]) {
                next.add(after[i]);
            }
        }
        return shrunk(account.next(next), List.of(), returned, owed, List.of());
    }

    private record Encoded(byte[] bytes, Refusal refusal) {}

    private static Encoded encode(Account next) {
        try {
            return new Encoded(AccountCodec.encode(next), null);
        } catch (AccountCodec.LimitExceededException over) {
            return new Encoded(null, over.refusal());
        }
    }
}
