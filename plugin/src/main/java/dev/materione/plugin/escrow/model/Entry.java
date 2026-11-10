package dev.materione.plugin.escrow.model;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * One escrowed stack (gui-input-durability.md §5.1).
 *
 * @param id          128-bit entry id, random, unique within an account; the ledger refers to it
 * @param state       HELD, OWED or EXITING
 * @param txn         the transaction of an EXITING entry; {@link Id128#ZERO} for every other state
 * @param sinceMillis when the entry was created (epoch millis; diagnostics only)
 * @param originGui   the GUI the stack was put into (diagnostics only; at most 65535 UTF-8 bytes)
 * @param slotHint    the slot it was in (diagnostics only; 0..65535)
 * @param stack       the stack
 */
public record Entry(
        Id128 id, EntryState state, Id128 txn, long sinceMillis, String originGui, int slotHint, StackBytes stack) {

    /** Largest slot hint the format holds (a {@code u16}). */
    public static final int MAX_SLOT_HINT = 0xFFFF;

    /** Largest origin text the format holds, in UTF-8 bytes. */
    public static final int MAX_ORIGIN_BYTES = 0xFFFF;

    /** @throws IllegalArgumentException when a field is outside what the format can hold or the txn rule is broken */
    public Entry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(txn, "txn");
        Objects.requireNonNull(originGui, "originGui");
        Objects.requireNonNull(stack, "stack");
        if (id.isZero()) {
            throw new IllegalArgumentException("an entry id cannot be zero");
        }
        if ((state == EntryState.EXITING) == txn.isZero()) {
            throw new IllegalArgumentException("txn is set exactly when the entry is EXITING");
        }
        if (slotHint < 0 || slotHint > MAX_SLOT_HINT) {
            throw new IllegalArgumentException("slotHint out of range: " + slotHint);
        }
        if (originGui.getBytes(StandardCharsets.UTF_8).length > MAX_ORIGIN_BYTES) {
            throw new IllegalArgumentException("originGui is longer than " + MAX_ORIGIN_BYTES + " bytes");
        }
    }

    /** A new HELD entry. */
    public static Entry held(Id128 id, StackBytes stack, String originGui, int slotHint, long sinceMillis) {
        return new Entry(id, EntryState.HELD, Id128.ZERO, sinceMillis, originGui, slotHint, stack);
    }

    /** This entry as OWED (no txn). */
    public Entry owed() {
        return new Entry(id, EntryState.OWED, Id128.ZERO, sinceMillis, originGui, slotHint, stack);
    }

    /** This entry frozen as EXITING for {@code transaction}. */
    public Entry exiting(Id128 transaction) {
        return new Entry(id, EntryState.EXITING, transaction, sinceMillis, originGui, slotHint, stack);
    }

    /** This entry with another stack (an in-place edit). */
    public Entry withStack(StackBytes edited) {
        return new Entry(id, state, txn, sinceMillis, originGui, slotHint, edited);
    }

    /** This entry in another slot. */
    public Entry withSlotHint(int slot) {
        return new Entry(id, state, txn, sinceMillis, originGui, slot, stack);
    }
}
