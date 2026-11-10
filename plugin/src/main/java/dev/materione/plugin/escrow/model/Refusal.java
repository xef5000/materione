package dev.materione.plugin.escrow.model;

/**
 * Why a strict transfer was refused (invariant I7: a transfer that cannot be recorded does nothing and says so;
 * it never degrades silently). The runtime (row P) maps these to the player message and the diagnostic; this row
 * assigns no diagnostic code.
 */
public enum Refusal {

    /** The account would hold more than {@link AccountCodec#MAX_ENTRIES} entries. */
    ENTRY_CAP,
    /** The encoded account would be larger than {@link AccountCodec#MAX_BYTES}. */
    SIZE_CAP,
    /** The request names an entry (or transaction) the account does not hold in a state that allows it. */
    STALE,
    /** The entry is EXITING: a consume decision is in flight, the slot is frozen. */
    BUSY,
    /** The stack to move in is not in the inventory. */
    NO_SUCH_STACK,
    /** Nothing fits in the inventory and the entry is already owed: nothing changed. */
    INVENTORY_FULL,
    /** The exit's INTENT is already durable: after the decision there is no abort. */
    DECIDED,
    /** The exit has no durable INTENT yet: step 3 (apply the deletion) may not run before the decision is durable. */
    UNDECIDED,
    /** The request itself is malformed (a zero transaction, a slot hint out of range, ...). */
    INVALID
}
