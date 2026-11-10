package dev.materione.plugin.escrow.model;

import java.util.Objects;

/** What reading a player's ledger file gave (gui-input-durability.md §5.3, §5.7). */
public sealed interface LedgerRead {

    /**
     * Every record before the end is valid. A torn tail (a kill mid-append) is not a record: it is ignored, and the
     * next writer truncates the file to {@code validLength} before it appends (K17).
     *
     * @param ledger      the valid records
     * @param validLength the byte length of the valid prefix (0 when even the header was torn: write it again)
     * @param tornBytes   how many trailing bytes were ignored (0 for a clean file)
     */
    record Valid(Ledger ledger, int validLength, int tornBytes) implements LedgerRead {
        public Valid {
            Objects.requireNonNull(ledger, "ledger");
        }
    }

    /**
     * A bad record before the end, or a header that is not a ledger's: corruption. The file is quarantined by the
     * IO half (row L, {@code .corrupt-<millis>}) and the player's strict transfers are refused (K18); the model
     * never guesses.
     *
     * @param offset where the first bad byte is
     * @param why    a short, stable cause for the diagnostic
     */
    record Corrupt(int offset, String why) implements LedgerRead {
        public Corrupt {
            Objects.requireNonNull(why, "why");
        }
    }

    /**
     * The file is a newer version than this reader: opaque and read-only (plugin.md §7.1), never appended to.
     *
     * @param version the version found
     */
    record Newer(int version) implements LedgerRead {}
}
