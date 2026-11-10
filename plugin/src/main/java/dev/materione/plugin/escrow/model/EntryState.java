package dev.materione.plugin.escrow.model;

/** The state of one escrowed stack (gui-input-durability.md §5.1). The {@code code} is the account format's byte. */
public enum EntryState {

    /** In an input slot of the open window. */
    HELD(1),
    /** To be returned to the player; there was no room yet. */
    OWED(2),
    /** Frozen: a consume decision is being made ({@code txn} set); release, close and quit do not return it. */
    EXITING(3);

    private final int code;

    EntryState(int code) {
        this.code = code;
    }

    /** The byte this state has in the account format. */
    public int code() {
        return code;
    }

    /** The state with this format byte, or {@code null} when none has it. */
    public static EntryState ofCode(int code) {
        for (EntryState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        return null;
    }
}
