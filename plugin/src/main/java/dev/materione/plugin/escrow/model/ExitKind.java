package dev.materione.plugin.escrow.model;

/** What an exit does with the stack (gui-input-durability.md §5.1). The {@code code} is the ledger format's byte. */
public enum ExitKind {

    /** The stack is deleted (the only exit built today: #248). */
    CONSUME(1),
    /** Reserved: a hand-off to a world drop, another player or a chest; it uses the same records. */
    HAND_OFF(2);

    private final int code;

    ExitKind(int code) {
        this.code = code;
    }

    /** The byte this kind has in an INTENT record. */
    public int code() {
        return code;
    }

    /** The kind with this byte, or {@code null} when none has it. */
    public static ExitKind ofCode(int code) {
        for (ExitKind kind : values()) {
            if (kind.code == code) {
                return kind;
            }
        }
        return null;
    }
}
