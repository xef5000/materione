package dev.materione.plugin.escrow.model;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * One item stack as the opaque bytes the platform serialises it to (gui-input-durability.md F13); the model never
 * looks inside. Value semantics: two stacks are equal when their bytes are.
 */
public final class StackBytes {

    private final byte[] bytes;

    private StackBytes(byte[] bytes) {
        this.bytes = bytes;
    }

    /** Wraps a copy of {@code bytes}. */
    public static StackBytes of(byte[] bytes) {
        return new StackBytes(bytes.clone());
    }

    /** A copy of the bytes. */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** The byte length. */
    public int length() {
        return bytes.length;
    }

    void writeTo(ByteBuffer out) {
        out.put(bytes);
    }

    /** The first 16 bytes of the SHA-256 of the stack: the {@code stackHash16} of a ledger INTENT (§5.3). */
    public Id128 hash16() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return Id128.read(ByteBuffer.wrap(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", impossible);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StackBytes that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        int shown = Math.min(bytes.length, 8);
        return "Stack[" + bytes.length + "B " + HexFormat.of().formatHex(bytes, 0, shown)
                + (bytes.length > shown ? "..." : "") + "]";
    }
}
