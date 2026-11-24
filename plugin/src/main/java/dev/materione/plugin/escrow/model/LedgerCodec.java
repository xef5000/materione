package dev.materione.plugin.escrow.model;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.nio.charset.StandardCharsets;

/**
 * The ledger's wire format, v1 (gui-input-durability.md §5.3), big-endian:
 *
 * <pre>
 * file   : "MTRL" | u16 version = 1 | record*
 * record : u32 length | u32 crc32(type + payload) | u8 type | payload      (length counts type + payload)
 * INTENT : type 1, 16B txn | 16B entryId | u8 kind | 16B stackHash16 | u32 amount | u64 atMillis
 * DONE   : type 2, 16B txn
 * </pre>
 *
 * <p>The decision point of an exit is a CRC-valid INTENT. What the reader does with a file:
 *
 * <ul>
 *   <li>a record whose bytes are cut short, or whose CRC fails <em>and that ends the file</em>, is a torn tail:
 *       not a record, ignored, truncated by the next writer (K17);
 *   <li>a record that fails its CRC, or is malformed, <em>before the end</em> is corruption (K18). A length
 *       beyond {@value #MAX_BODY} is corruption too, never a "short" tail: an append is one write, so a prefix of
 *       a record always carries its true length; reading a garbled mid-file length as a tail would silently drop
 *       the later INTENTs and resurrect their entries;
 *   <li>a tail of zero bytes (a file extended and not filled) is a torn tail.
 * </ul>
 */
public final class LedgerCodec {

    /** The file magic. */
    public static final byte[] MAGIC = {'M', 'T', 'R', 'L'};

    /** The version this code writes. */
    public static final int VERSION = 2;

    /** The oldest version this code reads. */
    public static final int MIN_READABLE_VERSION = 1;

    /** Header length: magic and version. */
    public static final int HEADER_LENGTH = 6;

    /** Largest record body (type + payload) a reader accepts. */
    public static final int MAX_BODY = 1024;

    static final int TYPE_INTENT = 1;
    static final int TYPE_DONE = 2;
    static final int TYPE_TRANSFER_INTENT = 3;
    static final int TYPE_TRANSFER_DONE = 4;

    private static final int RECORD_HEAD = 8;
    private static final int INTENT_PAYLOAD = 16 + 16 + 1 + 16 + 4 + 8;
    private static final int DONE_PAYLOAD = 16;

    private LedgerCodec() {}

    /** The six header bytes of a new file. */
    public static byte[] header() {
        return header(1);
    }

    /** Header chosen from the first record type; the consume-only v1 stream remains byte-identical. */
    public static byte[] headerFor(LedgerRecord record) {
        return record instanceof LedgerRecord.TransferIntent || record instanceof LedgerRecord.TransferDone
                ? header(VERSION) : header();
    }

    /** The bytes one append writes. */
    public static byte[] encodeRecord(LedgerRecord record) {
        ByteBuffer body = switch (record) {
            case LedgerRecord.Intent intent -> {
                ByteBuffer b = ByteBuffer.allocate(1 + INTENT_PAYLOAD).put((byte) TYPE_INTENT);
                intent.txn().write(b);
                intent.entryId().write(b);
                b.put((byte) intent.kind().code());
                intent.stackHash().write(b);
                b.putInt((int) intent.amount()).putLong(intent.atMillis());
                yield b;
            }
            case LedgerRecord.Done done -> {
                ByteBuffer b = ByteBuffer.allocate(1 + DONE_PAYLOAD).put((byte) TYPE_DONE);
                done.txn().write(b);
                yield b;
            }
            case LedgerRecord.TransferIntent intent -> {
                byte[] sourceKey = intent.source().keyBytes();
                byte[] destinationKey = intent.destination().keyBytes();
                int size = 1 + 16
                        + holderBytes(sourceKey.length) + 16 + 8
                        + holderBytes(destinationKey.length) + 16 + 8
                        + 16 + 4 + 8;
                ByteBuffer b = ByteBuffer.allocate(size).put((byte) TYPE_TRANSFER_INTENT);
                intent.txn().write(b);
                writeHolder(b, intent.source(), sourceKey);
                intent.sourceEntry().write(b);
                b.putLong(intent.sourceGen());
                writeHolder(b, intent.destination(), destinationKey);
                intent.destinationReservation().write(b);
                b.putLong(intent.destinationGen());
                intent.stackHash().write(b);
                b.putInt((int) intent.amount()).putLong(intent.atMillis());
                yield b;
            }
            case LedgerRecord.TransferDone done -> {
                ByteBuffer b = ByteBuffer.allocate(1 + DONE_PAYLOAD).put((byte) TYPE_TRANSFER_DONE);
                done.txn().write(b);
                yield b;
            }
        };
        byte[] bodyBytes = body.array();
        CRC32 crc = new CRC32();
        crc.update(bodyBytes);
        return ByteBuffer.allocate(RECORD_HEAD + bodyBytes.length)
                .putInt(bodyBytes.length)
                .putInt((int) crc.getValue())
                .put(bodyBytes)
                .array();
    }

    /** A whole file: the header and every record. */
    public static byte[] encode(Ledger ledger) {
        List<byte[]> parts = new ArrayList<>();
        int size = HEADER_LENGTH;
        for (LedgerRecord record : ledger.records()) {
            byte[] part = encodeRecord(record);
            parts.add(part);
            size += part.length;
        }
        int version = ledger.records().stream().anyMatch(record ->
                record instanceof LedgerRecord.TransferIntent || record instanceof LedgerRecord.TransferDone)
                ? VERSION : 1;
        ByteBuffer out = ByteBuffer.allocate(size).put(header(version));
        parts.forEach(out::put);
        return out.array();
    }

    /** Reads a whole file; never throws. An absent file is {@code new byte[0]}: an empty valid ledger. */
    public static LedgerRead read(byte[] file) {
        int n = file.length;
        if (n < HEADER_LENGTH) {
            byte[] header = header();
            for (int i = 0; i < n; i++) {
                if (file[i] != header[i]) {
                    return new LedgerRead.Corrupt(i, "not a ledger header");
                }
            }
            // an empty file, or a header cut short by a kill while the file was being created
            return new LedgerRead.Valid(Ledger.EMPTY, 0, n);
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (file[i] != MAGIC[i]) {
                return new LedgerRead.Corrupt(i, "not a ledger header");
            }
        }
        int version = ByteBuffer.wrap(file, MAGIC.length, 2).getShort() & 0xFFFF;
        if (version > VERSION) {
            return new LedgerRead.Newer(version);
        }
        if (version < MIN_READABLE_VERSION) {
            return new LedgerRead.Corrupt(MAGIC.length, "unsupported version " + version);
        }
        List<LedgerRecord> records = new ArrayList<>();
        int pos = HEADER_LENGTH;
        while (pos < n) {
            int remaining = n - pos;
            if (remaining < RECORD_HEAD) {
                return torn(records, pos, n);
            }
            ByteBuffer head = ByteBuffer.wrap(file, pos, RECORD_HEAD);
            long length = head.getInt() & 0xFFFFFFFFL;
            long storedCrc = head.getInt() & 0xFFFFFFFFL;
            if (length == 0 && allZero(file, pos)) {
                return torn(records, pos, n);
            }
            if (length < 1 || length > MAX_BODY) {
                return new LedgerRead.Corrupt(pos, "record length " + length);
            }
            long end = pos + RECORD_HEAD + length;
            if (end > n) {
                return torn(records, pos, n);
            }
            int bodyStart = pos + RECORD_HEAD;
            CRC32 crc = new CRC32();
            crc.update(file, bodyStart, (int) length);
            if (crc.getValue() != storedCrc) {
                if (end == n) {
                    return torn(records, pos, n);
                }
                return new LedgerRead.Corrupt(pos, "record checksum");
            }
            LedgerRecord record = parse(file, bodyStart, (int) length, version);
            if (record == null) {
                return new LedgerRead.Corrupt(pos, "malformed record");
            }
            records.add(record);
            pos = (int) end;
        }
        return new LedgerRead.Valid(new Ledger(records), n, 0);
    }

    private static LedgerRead torn(List<LedgerRecord> records, int validEnd, int fileLength) {
        return new LedgerRead.Valid(new Ledger(records), validEnd, fileLength - validEnd);
    }

    private static boolean allZero(byte[] file, int from) {
        for (int i = from; i < file.length; i++) {
            if (file[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** One record from a body whose CRC held; {@code null} when its type, size or content is not v1's. */
    private static LedgerRecord parse(byte[] file, int start, int length, int version) {
        ByteBuffer body = ByteBuffer.wrap(file, start, length);
        try {
            int type = body.get() & 0xFF;
            if (type == TYPE_INTENT && length == 1 + INTENT_PAYLOAD) {
                Id128 txn = Id128.read(body);
                Id128 entryId = Id128.read(body);
                ExitKind kind = ExitKind.ofCode(body.get() & 0xFF);
                Id128 hash = Id128.read(body);
                long amount = body.getInt() & 0xFFFFFFFFL;
                long at = body.getLong();
                return kind == null ? null : new LedgerRecord.Intent(txn, entryId, kind, hash, amount, at);
            }
            if (type == TYPE_DONE && length == 1 + DONE_PAYLOAD) {
                return new LedgerRecord.Done(Id128.read(body));
            }
            if (version >= 2 && type == TYPE_TRANSFER_INTENT) {
                Id128 txn = Id128.read(body);
                TransferHolder source = readHolder(body);
                Id128 sourceEntry = Id128.read(body);
                long sourceGen = body.getLong();
                TransferHolder destination = readHolder(body);
                Id128 destinationReservation = Id128.read(body);
                long destinationGen = body.getLong();
                Id128 hash = Id128.read(body);
                long amount = body.getInt() & 0xFFFFFFFFL;
                long at = body.getLong();
                if (body.hasRemaining()) {
                    return null;
                }
                return new LedgerRecord.TransferIntent(txn, source, sourceEntry, sourceGen,
                        destination, destinationReservation, destinationGen, hash, amount, at);
            }
            if (version >= 2 && type == TYPE_TRANSFER_DONE && length == 1 + DONE_PAYLOAD) {
                return new LedgerRecord.TransferDone(Id128.read(body));
            }
            return null;
        } catch (BufferUnderflowException | IllegalArgumentException malformed) {
            return null;
        }
    }

    private static int holderBytes(int keyLength) {
        return 16 + 1 + 2 + keyLength;
    }

    private static byte[] header(int version) {
        return ByteBuffer.allocate(HEADER_LENGTH).put(MAGIC).putShort((short) version).array();
    }

    private static void writeHolder(ByteBuffer out, TransferHolder holder, byte[] key) {
        holder.owner().write(out);
        out.put((byte) holder.kind().code()).putShort((short) key.length).put(key);
    }

    private static TransferHolder readHolder(ByteBuffer in) {
        Id128 owner = Id128.read(in);
        TransferHolder.Kind kind = TransferHolder.Kind.ofCode(in.get() & 0xFF);
        int length = in.getShort() & 0xFFFF;
        if (kind == null || length > TransferHolder.MAX_KEY_BYTES || length > in.remaining()) {
            throw new IllegalArgumentException("invalid transfer holder");
        }
        byte[] key = new byte[length];
        in.get(key);
        String text = new String(key, StandardCharsets.UTF_8);
        return new TransferHolder(owner, kind, text);
    }
}
