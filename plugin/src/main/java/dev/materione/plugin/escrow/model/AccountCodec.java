package dev.materione.plugin.escrow.model;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * The account's wire format, v1 (gui-input-durability.md §5.3), big-endian:
 *
 * <pre>
 * u8   magic 0x4D   u8 version = 1
 * u64  gen
 * u16  entryCount
 * entry*  : 16B id | u8 state | 16B txn (zero unless EXITING) | u64 sinceMillis
 *           | u16 len + utf8 originGui | u16 slotHint | u32 len + stack bytes
 * u32  crc32 over everything above
 * </pre>
 *
 * <p>Decoding then encoding a readable account is byte-identical (the encoding is canonical: strict UTF-8, no
 * padding). A reader accepts versions {@value #MIN_READABLE_VERSION} to {@value #VERSION} (v1 is the first, so
 * "N-1" is empty today) and treats a newer version as opaque ({@link AccountRead.Reason#NEWER_VERSION}).
 * The caps of the owner's answer (§14 Q7) bind {@link #encode}, not {@link #decode}: an account this plugin wrote
 * is within them, and refusing to read an account that is merely large would be guessing.
 */
public final class AccountCodec {

    /** First byte of every account. */
    public static final int MAGIC = 0x4D;

    /** The version this code writes. */
    public static final int VERSION = 1;

    /** The oldest version this code reads. */
    public static final int MIN_READABLE_VERSION = 1;

    /** Most entries an account may hold (§14 Q7). */
    public static final int MAX_ENTRIES = 54;

    /** Largest encoded account, in bytes (§14 Q7: 256 KiB). */
    public static final int MAX_BYTES = 256 * 1024;

    private static final int HEADER = 1 + 1 + 8 + 2;
    private static final int CRC = 4;
    private static final int FIXED_ENTRY = Id128.BYTES + 1 + Id128.BYTES + 8 + 2 + 2 + 4;

    private AccountCodec() {}

    /** Thrown by {@link #encode} when the account is over a cap; carries the refusal the transfer reports. */
    public static final class LimitExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Refusal refusal;

        LimitExceededException(Refusal refusal, String message) {
            super(message);
            this.refusal = refusal;
        }

        /** {@link Refusal#ENTRY_CAP} or {@link Refusal#SIZE_CAP}. */
        public Refusal refusal() {
            return refusal;
        }
    }

    /**
     * The bytes of {@code account}.
     *
     * @throws LimitExceededException when it holds more than {@link #MAX_ENTRIES} entries or would encode to more
     *                                than {@link #MAX_BYTES} bytes
     */
    public static byte[] encode(Account account) {
        return encode(account, true);
    }

    /**
     * As {@link #encode}, without the caps: for results that are no larger than the account they came from
     * (releases, recovery), which must never be refused because an account is over a cap it was not written under.
     */
    static byte[] encodeUnchecked(Account account) {
        return encode(account, false);
    }

    private static byte[] encode(Account account, boolean enforceCaps) {
        if (enforceCaps && account.entries().size() > MAX_ENTRIES) {
            throw new LimitExceededException(Refusal.ENTRY_CAP,
                    account.entries().size() + " entries, the cap is " + MAX_ENTRIES);
        }
        long size = HEADER + CRC;
        List<byte[]> origins = new ArrayList<>(account.entries().size());
        for (Entry entry : account.entries()) {
            byte[] origin = entry.originGui().getBytes(StandardCharsets.UTF_8);
            origins.add(origin);
            size += FIXED_ENTRY + origin.length + entry.stack().length();
            if (enforceCaps && size > MAX_BYTES) {
                throw new LimitExceededException(Refusal.SIZE_CAP, "the account is over " + MAX_BYTES + " bytes");
            }
        }
        ByteBuffer out = ByteBuffer.allocate((int) size);
        out.put((byte) MAGIC).put((byte) VERSION).putLong(account.gen()).putShort((short) account.entries().size());
        for (int i = 0; i < account.entries().size(); i++) {
            Entry entry = account.entries().get(i);
            byte[] origin = origins.get(i);
            entry.id().write(out);
            out.put((byte) entry.state().code());
            entry.txn().write(out);
            out.putLong(entry.sinceMillis());
            out.putShort((short) origin.length).put(origin);
            out.putShort((short) entry.slotHint());
            out.putInt(entry.stack().length());
            entry.stack().writeTo(out);
        }
        out.putInt((int) crc(out.array(), out.position()));
        return out.array();
    }

    /** Reads the persistent value, absent when the player has none. */
    public static AccountRead read(byte[] storedOrNull) {
        return storedOrNull == null ? new AccountRead.Absent() : decode(storedOrNull);
    }

    /** Decodes {@code bytes}; never throws: an unreadable account is a value that keeps its raw bytes. */
    public static AccountRead decode(byte[] bytes) {
        if (bytes.length < 2) {
            return unreadable(AccountRead.Reason.TRUNCATED, bytes);
        }
        if ((bytes[0] & 0xFF) != MAGIC) {
            return unreadable(AccountRead.Reason.BAD_MAGIC, bytes);
        }
        int version = bytes[1] & 0xFF;
        if (version > VERSION) {
            return unreadable(AccountRead.Reason.NEWER_VERSION, bytes);
        }
        if (version < MIN_READABLE_VERSION) {
            return unreadable(AccountRead.Reason.UNSUPPORTED_VERSION, bytes);
        }
        if (bytes.length < HEADER + CRC) {
            return unreadable(AccountRead.Reason.TRUNCATED, bytes);
        }
        int bodyEnd = bytes.length - CRC;
        long stored = ByteBuffer.wrap(bytes, bodyEnd, CRC).getInt() & 0xFFFFFFFFL;
        if (stored != crc(bytes, bodyEnd)) {
            return unreadable(AccountRead.Reason.BAD_CRC, bytes);
        }
        try {
            ByteBuffer in = ByteBuffer.wrap(bytes, 2, bodyEnd - 2);
            long gen = in.getLong();
            int count = in.getShort() & 0xFFFF;
            List<Entry> entries = new ArrayList<>(Math.min(count, MAX_ENTRIES));
            for (int i = 0; i < count; i++) {
                entries.add(readEntry(in));
            }
            if (in.hasRemaining()) {
                return unreadable(AccountRead.Reason.MALFORMED, bytes);
            }
            return new AccountRead.Readable(new Account(gen, entries));
        } catch (BufferUnderflowException | IllegalArgumentException | CharacterCodingException malformed) {
            return unreadable(AccountRead.Reason.MALFORMED, bytes);
        }
    }

    private static Entry readEntry(ByteBuffer in) throws CharacterCodingException {
        Id128 id = Id128.read(in);
        EntryState state = EntryState.ofCode(in.get() & 0xFF);
        if (state == null) {
            throw new IllegalArgumentException("unknown state");
        }
        Id128 txn = Id128.read(in);
        long since = in.getLong();
        int originLength = in.getShort() & 0xFFFF;
        if (originLength > in.remaining()) {
            throw new BufferUnderflowException();
        }
        byte[] origin = new byte[originLength];
        in.get(origin);
        String originGui = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(origin))
                .toString();
        int slotHint = in.getShort() & 0xFFFF;
        int stackLength = in.getInt();
        if (stackLength < 0 || stackLength > in.remaining()) {
            throw new BufferUnderflowException();
        }
        byte[] stack = new byte[stackLength];
        in.get(stack);
        return new Entry(id, state, txn, since, originGui, slotHint, StackBytes.of(stack));
    }

    private static AccountRead unreadable(AccountRead.Reason reason, byte[] raw) {
        return new AccountRead.Unreadable(reason, raw);
    }

    private static long crc(byte[] data, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, length);
        return crc.getValue();
    }
}
