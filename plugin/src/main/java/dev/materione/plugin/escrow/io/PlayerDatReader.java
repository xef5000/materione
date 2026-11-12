package dev.materione.plugin.escrow.io;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Reads one value out of a player's {@code <uuid>.dat}: the gzip-compressed NBT the server writes for a player
 * (gui-input-durability.md §3 F1, §11 P6). It looks for {@code BukkitValues -> <key>} (the plugin persistent data
 * compound and the account's key, {@code materione:gui_escrow}) and returns the byte array stored there; every other
 * tag is skipped without being built, so the cost is a decompression pass, not an object tree.
 *
 * <p><b>It reads to the end.</b> A value found is only returned once the whole stream has been consumed, which makes
 * the gzip layer check its trailer (length and CRC32) and the NBT layer check that the root compound really ended: a
 * file cut short, flipped in a byte or still being written is an {@link IOException}, never a value. This is what
 * lets the barrier trust a read of a file it did not write (F2: the server replaces the file by two renames, so a
 * reader may meet it between them or open it as it is replaced).
 *
 * <p>Read only, no state, any thread; it lives in the IO half, so it never sees a Bukkit type.
 */
public final class PlayerDatReader {

    /** More decompressed bytes than this is not a player file: the read stops with an {@link IOException}. */
    static final long MAX_DECOMPRESSED = 64L << 20;

    private static final int MAX_DEPTH = 512;

    private static final byte END = 0;
    private static final byte BYTE_ARRAY = 7;
    private static final byte STRING = 8;
    private static final byte LIST = 9;
    private static final byte COMPOUND = 10;
    private static final byte INT_ARRAY = 11;
    private static final byte LONG_ARRAY = 12;

    private PlayerDatReader() {}

    /**
     * The byte array stored at {@code BukkitValues -> key} of the file, or empty when the file has no plugin data or
     * no such value (or it is not a byte array).
     *
     * @throws java.nio.file.NoSuchFileException when the file does not exist
     * @throws IOException on any other failure: unreadable, not gzip, not NBT, truncated, a bad trailer, oversized
     */
    public static Optional<byte[]> readValue(Path file, String key) throws IOException {
        // the file is held open for one read of its bytes and not a moment longer: on a platform where an open file
        // blocks the server's rename of it (Windows), a slow parse would make the server's own save fail (F2)
        byte[] compressed = readAtMost(file, MAX_DECOMPRESSED);
        try (InputStream in = new BufferedInputStream(new Bounded(
                new GZIPInputStream(new java.io.ByteArrayInputStream(compressed)), MAX_DECOMPRESSED))) {
            return readValue(in, key);
        }
    }

    private static byte[] readAtMost(Path file, long limit) throws IOException {
        try (InputStream raw = Files.newInputStream(file)) {
            byte[] bytes = raw.readNBytes((int) limit + 1);
            if (bytes.length > limit) {
                throw new IOException("the file is larger than " + limit + " bytes");
            }
            return bytes;
        }
    }

    /** As {@link #readValue(Path, String)} over an already-decompressed NBT stream; the stream is drained. */
    static Optional<byte[]> readValue(InputStream decompressed, String key) throws IOException {
        DataInputStream in = new DataInputStream(decompressed);
        if (in.readByte() != COMPOUND) {
            throw new IOException("not a compound root");
        }
        in.readUTF();
        byte[] found = null;
        for (byte type = in.readByte(); type != END; type = in.readByte()) {
            String name = in.readUTF();
            if (type == COMPOUND && name.equals("BukkitValues")) {
                found = scanValues(in, key);
            } else {
                skip(in, type, 0);
            }
        }
        in.transferTo(OutputStream.nullOutputStream()); // the gzip trailer is only verified at the end of the stream
        return Optional.ofNullable(found);
    }

    private static byte[] scanValues(DataInputStream in, String key) throws IOException {
        byte[] found = null;
        for (byte type = in.readByte(); type != END; type = in.readByte()) {
            String name = in.readUTF();
            if (type == BYTE_ARRAY && name.equals(key)) {
                byte[] value = new byte[checkedLength(in.readInt(), 1)];
                in.readFully(value);
                found = value;
            } else {
                skip(in, type, 1);
            }
        }
        return found;
    }

    private static void skip(DataInputStream in, byte type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nested deeper than " + MAX_DEPTH);
        }
        switch (type) {
            case 1 -> in.skipNBytes(1);
            case 2 -> in.skipNBytes(2);
            case 3, 5 -> in.skipNBytes(4);
            case 4, 6 -> in.skipNBytes(8);
            case BYTE_ARRAY -> in.skipNBytes(checkedLength(in.readInt(), 1));
            case STRING -> in.skipNBytes(in.readUnsignedShort());
            case LIST -> {
                byte element = in.readByte();
                int size = in.readInt();
                if (size < 0 || (element == END && size > 0)) {
                    throw new IOException("bad NBT list: " + size + " of type " + element);
                }
                for (int i = 0; i < size; i++) {
                    skip(in, element, depth + 1);
                }
            }
            case COMPOUND -> {
                for (byte next = in.readByte(); next != END; next = in.readByte()) {
                    in.readUTF();
                    skip(in, next, depth + 1);
                }
            }
            case INT_ARRAY -> in.skipNBytes((long) checkedLength(in.readInt(), 4) * 4);
            case LONG_ARRAY -> in.skipNBytes((long) checkedLength(in.readInt(), 8) * 8);
            default -> throw new IOException("unknown NBT tag type " + type);
        }
    }

    private static int checkedLength(int length, int width) throws IOException {
        if (length < 0 || (long) length * width > MAX_DECOMPRESSED) {
            throw new IOException("bad NBT array length " + length);
        }
        return length;
    }

    /** Fails once more than {@code limit} bytes were read: a guard against a hostile or runaway file. */
    private static final class Bounded extends InputStream {

        private final InputStream in;
        private long left;

        Bounded(InputStream in, long limit) {
            this.in = in;
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) {
                int probe = in.read();
                if (probe < 0) {
                    return -1; // the stream ended exactly at the limit
                }
                throw new IOException("decompressed size exceeds " + MAX_DECOMPRESSED + " bytes");
            }
            int n = in.read(buffer, offset, (int) Math.min(length, left));
            if (n > 0) {
                left -= n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
