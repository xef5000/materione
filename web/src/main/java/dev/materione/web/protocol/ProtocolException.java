package dev.materione.web.protocol;

/**
 * A wire-protocol-level failure (web.md §4/§5): a bad signature, a sequence
 * gap or replay, an undecryptable frame, a malformed message. Every one of
 * these is treated as hostile: the receiving side drops the session instead
 * of trying to resynchronize — there is no recovery path for a broken
 * channel, only a clean reconnect with a new handshake.
 *
 * <p>Protocol violations are diagnosable ({@code MTR-0033}) but never crash
 * anything (D6): the listener stays up, other sessions are unaffected.
 */
public class ProtocolException extends RuntimeException {

    /** The broad failure class — drives the error the peer sees (if any). */
    public enum Kind {
        /** Structural problem: not JSON, wrong shape, missing fields. */
        MALFORMED,
        /** Signature missing, wrong, or over unexpected bytes. */
        BAD_SIGNATURE,
        /** AES-GCM authentication failed (wrong key, tampered ciphertext). */
        BAD_CIPHERTEXT,
        /** Sequence number gap, replay, or reorder — rejected outright. */
        BAD_SEQUENCE,
        /** Handshake step out of order, unknown session, key mismatch. */
        HANDSHAKE,
        /** Well-formed but semantically unusable (unknown op is NOT this —
         *  unknown ops get a clean error response, never a disconnect). */
        UNSUPPORTED
    }

    private final Kind kind;

    public ProtocolException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ProtocolException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
