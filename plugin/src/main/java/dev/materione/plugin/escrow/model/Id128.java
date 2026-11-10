package dev.materione.plugin.escrow.model;

import java.nio.ByteBuffer;
import java.util.HexFormat;

/**
 * A 128-bit identifier: an entry id, a transaction id, or a stack hash (gui-input-durability.md §5.1, §5.3).
 * Random in production (the caller supplies it, so the model stays deterministic); the all-zero value means
 * "none" (an entry that is not EXITING has no transaction).
 *
 * @param hi the first eight bytes, big-endian
 * @param lo the last eight bytes, big-endian
 */
public record Id128(long hi, long lo) {

    /** The "no value" id. */
    public static final Id128 ZERO = new Id128(0L, 0L);

    /** Width in the formats, in bytes. */
    public static final int BYTES = 16;

    /** True for {@link #ZERO}. */
    public boolean isZero() {
        return hi == 0L && lo == 0L;
    }

    /** Reads 16 bytes at the buffer's position. */
    public static Id128 read(ByteBuffer in) {
        return new Id128(in.getLong(), in.getLong());
    }

    /** Writes 16 bytes at the buffer's position. */
    public void write(ByteBuffer out) {
        out.putLong(hi).putLong(lo);
    }

    /** The 32-digit lower-case hex form. */
    @Override
    public String toString() {
        return HexFormat.of().toHexDigits(hi) + HexFormat.of().toHexDigits(lo);
    }
}
