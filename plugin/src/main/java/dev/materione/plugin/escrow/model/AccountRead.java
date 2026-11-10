package dev.materione.plugin.escrow.model;

import java.util.Objects;

/**
 * What reading the player's persistent {@code materione:gui_escrow} value gave (gui-input-durability.md §5.3, §5.7).
 */
public sealed interface AccountRead {

    /** Why an account is unreadable. */
    enum Reason {
        /** The first byte is not the account magic. */
        BAD_MAGIC,
        /** A version newer than this reader: the bytes are opaque and read-only (plugin.md §7.1). */
        NEWER_VERSION,
        /** A version older than the oldest this reader accepts. */
        UNSUPPORTED_VERSION,
        /** Shorter than the fixed header and checksum. */
        TRUNCATED,
        /** The CRC does not match the bytes. */
        BAD_CRC,
        /** The checksum matches but the content is not a v1 account (bad state byte, duplicate id, trailing bytes). */
        MALFORMED
    }

    /** There is no value: the player never escrowed anything. */
    record Absent() implements AccountRead {}

    /** A well-formed account. */
    record Readable(Account account) implements AccountRead {
        public Readable {
            Objects.requireNonNull(account, "account");
        }
    }

    /**
     * Unreadable. The raw bytes are preserved untouched: guessing is the one way to destroy items, so nothing is
     * returned or deleted and the player's strict transfers are refused (K18).
     */
    record Unreadable(Reason reason, byte[] raw) implements AccountRead {
        public Unreadable {
            Objects.requireNonNull(reason, "reason");
            raw = raw.clone();
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Unreadable that && reason == that.reason && java.util.Arrays.equals(raw, that.raw);
        }

        @Override
        public int hashCode() {
            return 31 * reason.hashCode() + java.util.Arrays.hashCode(raw);
        }

        @Override
        public String toString() {
            return "Unreadable[" + reason + ", " + raw.length + "B]";
        }
    }
}
