package dev.materione.harness;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;

/**
 * The harness's own, independent reading and writing of the two escrow formats of gui-input-durability.md section 5.3
 * (account v1 and ledger v1): enough to ask a saved account what it holds, to craft the states the product cannot make
 * yet (an EXITING entry, an INTENT record: tier 2 has no caller before #248), and to damage a file the way a crash
 * or a bad disk would. It is deliberately a second implementation of the layouts, written from the doc and not from
 * the plugin's codec: the harness depends on no product module (CLAUDE.md), and an independent reader is also a check
 * that the layout the plugin writes is the one the doc specifies.
 *
 * <pre>
 * account: u8 0x4D | u8 version | u64 gen | u16 count | entry* | u32 crc32 (everything before it)
 * entry  : 16B id | u8 state (1 HELD, 2 OWED, 3 EXITING) | 16B txn | u64 since | u16 len + utf8 origin
 *          | u16 slotHint | u32 len + stack bytes
 * ledger : "MTRL" | u16 version | record*      record: u32 length | u32 crc32(type + payload) | u8 type | payload
 * INTENT : 1, 16B txn | 16B entryId | u8 kind (1 consume) | 16B stackHash | u32 amount | u64 atMillis      DONE: 2, 16B txn
 * </pre>
 */
public final class EscrowFiles {

    /** Entry states of the account format. */
    public static final int HELD = 1;
    public static final int OWED = 2;
    public static final int EXITING = 3;

    private EscrowFiles() {}

    /** One account entry; {@code stack} is the opaque serialised item. */
    public record Entry(byte[] id, int state, byte[] txn, long since, String origin, int slotHint, byte[] stack) {

        public String idHex() {
            return HexFormat.of().formatHex(id);
        }

        /** The same entry, frozen under transaction {@code txn}. */
        public Entry exiting(byte[] txn) {
            return new Entry(id, EXITING, txn, since, origin, slotHint, stack);
        }
    }

    /** A decoded account. */
    public record Account(long gen, List<Entry> entries) {

        public Account {
            entries = List.copyOf(entries);
        }

        public Account withGen(long next) {
            return new Account(next, entries);
        }

        public List<Entry> inState(int state) {
            return entries.stream().filter(entry -> entry.state() == state).toList();
        }
    }

    /** Decodes an account; an unreadable one is an {@link IllegalArgumentException} naming why. */
    public static Account parseAccount(byte[] bytes) {
        if (bytes.length < 16 || (bytes[0] & 0xFF) != 0x4D) {
            throw new IllegalArgumentException("not an account (magic or length)");
        }
        if ((bytes[1] & 0xFF) != 1) {
            throw new IllegalArgumentException("account version " + (bytes[1] & 0xFF));
        }
        int bodyEnd = bytes.length - 4;
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bodyEnd);
        if ((int) crc.getValue() != ByteBuffer.wrap(bytes, bodyEnd, 4).getInt()) {
            throw new IllegalArgumentException("account crc");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes, 2, bodyEnd - 2);
        long gen = in.getLong();
        int count = in.getShort() & 0xFFFF;
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] id = new byte[16];
            in.get(id);
            int state = in.get() & 0xFF;
            byte[] txn = new byte[16];
            in.get(txn);
            long since = in.getLong();
            byte[] origin = new byte[in.getShort() & 0xFFFF];
            in.get(origin);
            int slotHint = in.getShort() & 0xFFFF;
            byte[] stack = new byte[in.getInt()];
            in.get(stack);
            entries.add(new Entry(id, state, txn, since, new String(origin, StandardCharsets.UTF_8), slotHint, stack));
        }
        if (in.hasRemaining()) {
            throw new IllegalArgumentException("account trailing bytes");
        }
        return new Account(gen, entries);
    }

    public static Account parseAccountHex(String hex) {
        return parseAccount(HexFormat.of().parseHex(hex));
    }

    public static byte[] encodeAccount(Account account) {
        int size = 2 + 8 + 2 + 4;
        for (Entry entry : account.entries()) {
            size += 16 + 1 + 16 + 8 + 2 + entry.origin().getBytes(StandardCharsets.UTF_8).length + 2 + 4
                    + entry.stack().length;
        }
        ByteBuffer out = ByteBuffer.allocate(size);
        out.put((byte) 0x4D).put((byte) 1).putLong(account.gen()).putShort((short) account.entries().size());
        for (Entry entry : account.entries()) {
            byte[] origin = entry.origin().getBytes(StandardCharsets.UTF_8);
            out.put(entry.id()).put((byte) entry.state()).put(entry.txn()).putLong(entry.since());
            out.putShort((short) origin.length).put(origin).putShort((short) entry.slotHint());
            out.putInt(entry.stack().length).put(entry.stack());
        }
        CRC32 crc = new CRC32();
        crc.update(out.array(), 0, out.position());
        out.putInt((int) crc.getValue());
        return out.array();
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** A transaction id that is easy to spot in a hex dump. */
    public static byte[] txn(int n) {
        byte[] txn = new byte[16];
        txn[0] = (byte) 0xAB;
        txn[15] = (byte) n;
        return txn;
    }

    // ---- the ledger -----------------------------------------------------------------------------------------------

    public static byte[] ledgerHeader() {
        return ByteBuffer.allocate(6).put(new byte[] {'M', 'T', 'R', 'L'}).putShort((short) 1).array();
    }

    /** One complete INTENT record: the durable decision to consume {@code entryId} under {@code txn}. */
    public static byte[] intent(byte[] txn, byte[] entryId, long atMillis) {
        ByteBuffer body = ByteBuffer.allocate(1 + 16 + 16 + 1 + 16 + 4 + 8);
        body.put((byte) 1).put(txn).put(entryId).put((byte) 1).put(new byte[16]).putInt(1).putLong(atMillis);
        return record(body.array());
    }

    public static byte[] done(byte[] txn) {
        return record(ByteBuffer.allocate(1 + 16).put((byte) 2).put(txn).array());
    }

    private static byte[] record(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body);
        return ByteBuffer.allocate(8 + body.length).putInt(body.length).putInt((int) crc.getValue()).put(body).array();
    }

    /** One valid ledger record as the harness reads it: {@code type} 1 INTENT or 2 DONE; {@code entry} is blank for a DONE. */
    public record Record(int type, String txnHex, String entryHex) {

        public boolean isIntent() {
            return type == 1;
        }

        public boolean isDone() {
            return type == 2;
        }
    }

    /**
     * The valid records of a ledger file, in order: reading stops at the first record that is torn (cut short, or whose
     * CRC fails), which is not a record (section 5.3). Empty when the file does not exist or has no complete header.
     */
    public static List<Record> readLedger(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        ByteBuffer in = ByteBuffer.wrap(Files.readAllBytes(file));
        if (in.remaining() < 6 || in.getInt() != 0x4D54524C /* MTRL */ || in.getShort() != 1) {
            return List.of();
        }
        List<Record> out = new ArrayList<>();
        while (in.remaining() >= 8) {
            int length = in.getInt();
            int crc = in.getInt();
            if (length < 1 || length > in.remaining()) {
                break;
            }
            byte[] body = new byte[length];
            in.get(body);
            CRC32 check = new CRC32();
            check.update(body);
            if ((int) check.getValue() != crc) {
                break;
            }
            int type = body[0] & 0xFF;
            String txn = HexFormat.of().formatHex(body, 1, 17);
            String entry = type == 1 ? HexFormat.of().formatHex(body, 17, 33) : "";
            out.add(new Record(type, txn, entry));
        }
        return out;
    }

    /** The valid records of a fake player's ledger. */
    public static List<Record> ledgerOf(CrashRig rig, String player) throws IOException {
        return readLedger(ledgerFile(rig, player));
    }

    public static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        ByteBuffer out = ByteBuffer.allocate(size);
        for (byte[] part : parts) {
            out.put(part);
        }
        return out.array();
    }

    public static byte[] prefix(byte[] bytes, int length) {
        return java.util.Arrays.copyOf(bytes, length);
    }

    /** A record whose CRC is wrong (one bit of the stored CRC flipped): in the middle of a file this is corruption. */
    public static byte[] withBadCrc(byte[] record) {
        byte[] copy = record.clone();
        copy[4] ^= 0x01;
        return copy;
    }

    // ---- where the files are --------------------------------------------------------------------------------------

    /** {@code plugins/Materione/storage/escrow/<uuid>.ledger} of a fake player. */
    public static Path ledgerFile(CrashRig rig, String player) {
        return escrowDir(rig).resolve(CrashRig.offlineUuid(player) + ".ledger");
    }

    public static Path escrowDir(CrashRig rig) {
        return rig.workspace().pluginsDir.resolve("Materione").resolve("storage").resolve("escrow");
    }

    public static void writeLedger(CrashRig rig, String player, byte[] bytes) throws IOException {
        Path file = ledgerFile(rig, player);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** The quarantined copies of a ledger ({@code <uuid>.ledger.corrupt-<millis>}). */
    public static List<Path> quarantined(CrashRig rig, String player) throws IOException {
        Path dir = escrowDir(rig);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        String prefix = CrashRig.offlineUuid(player) + ".ledger.corrupt-";
        try (var files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().startsWith(prefix)).toList();
        }
    }
}
