package dev.materione.plugin.escrow.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

/**
 * The byte-exact goldens of account v1 and ledger v1 (gui-input-durability.md §5.3, §8): {@code fixtures/escrow/}.
 * Each fixture is a hex file (lines starting with {@code #} are comments). Every fixture is built here from the model
 * and compared byte for byte with the committed file; every readable one decodes back to the same model and
 * re-encodes to the same bytes. The corpus only grows: a new format fact is a new fixture, an existing one never
 * changes ({@code -Dmaterione.golden.regen=true} is for adding one).
 */
class EscrowGoldenFixturesTest {

    private static final Path DIR = GoldenFiles.fixturesRoot().resolve("escrow");

    // ---- the account ------------------------------------------------------------------------------------------

    private static Account oneHeld() {
        return Fx.account(1, Fx.held(1, "diamond x3"));
    }

    private static Account mixed() {
        Entry owed = Entry.held(Fx.id(2), Fx.stack("bread x64"), "boutique é", 4, 2_000L).owed();
        Entry exiting = Entry.held(Fx.id(3), Fx.stack("emerald"), "shop", 0, 3_000L).exiting(Fx.id(0xAB));
        return Fx.account(9, Fx.held(1, "diamond x3"), owed, exiting);
    }

    @Test
    void accountEmpty() throws IOException {
        byte[] bytes = AccountCodec.encode(Fx.account(3));
        check("account-v1-empty", "an empty account, gen 3", bytes);
        assertThat(AccountCodec.decode(bytes)).isEqualTo(new AccountRead.Readable(Fx.account(3)));
        assertThat(AccountCodec.encode(((AccountRead.Readable) AccountCodec.decode(bytes)).account())).isEqualTo(bytes);
    }

    @Test
    void accountOneHeld() throws IOException {
        byte[] bytes = AccountCodec.encode(oneHeld());
        check("account-v1-one-held", "gen 1, one HELD entry (slot 1, origin shop, \"diamond x3\")", bytes);
        roundTrips(bytes, oneHeld());
    }

    @Test
    void accountOwedAndExiting() throws IOException {
        byte[] bytes = AccountCodec.encode(mixed());
        check("account-v1-owed-exiting", "gen 9: HELD, OWED (non-ASCII origin), EXITING (txn 0xAB)", bytes);
        roundTrips(bytes, mixed());
    }

    @Test
    void accountBadCrcIsUnreadableAndKeepsItsBytes() throws IOException {
        byte[] bytes = AccountCodec.encode(oneHeld());
        bytes[bytes.length - 6] ^= 0x01;
        check("account-v1-bad-crc", "account-v1-one-held with one stack byte flipped: CRC mismatch", bytes);
        AccountRead read = AccountCodec.decode(bytes);
        assertThat(read).isEqualTo(new AccountRead.Unreadable(AccountRead.Reason.BAD_CRC, bytes));
        assertThat(((AccountRead.Unreadable) read).raw()).isEqualTo(bytes);
    }

    @Test
    void accountNewerVersionIsOpaque() throws IOException {
        byte[] bytes = {0x4D, 0x02, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05};
        check("account-v1-newer-version", "magic, version 2, opaque payload: a newer writer's account", bytes);
        assertThat(AccountCodec.decode(bytes))
                .isEqualTo(new AccountRead.Unreadable(AccountRead.Reason.NEWER_VERSION, bytes));
    }

    @Test
    void accountTruncated() throws IOException {
        byte[] bytes = Arrays.copyOf(AccountCodec.encode(oneHeld()), 10);
        check("account-v1-truncated", "the first ten bytes of account-v1-one-held", bytes);
        assertThat(AccountCodec.decode(bytes))
                .isEqualTo(new AccountRead.Unreadable(AccountRead.Reason.TRUNCATED, bytes));
    }

    @Test
    void accountWithAnUnknownStateByteIsMalformedEvenWithAValidCrc() throws IOException {
        byte[] bytes = AccountCodec.encode(oneHeld());
        bytes[12 + 16] = 9; // the state byte of the first entry
        recrc(bytes);
        check("account-v1-bad-state", "valid CRC, state byte 9: not a v1 account", bytes);
        assertThat(AccountCodec.decode(bytes))
                .isEqualTo(new AccountRead.Unreadable(AccountRead.Reason.MALFORMED, bytes));
    }

    // ---- the ledger -------------------------------------------------------------------------------------------

    private static LedgerRecord.Intent intent(long txn, long entry) {
        return new LedgerRecord.Intent(
                Fx.id(txn), Fx.id(entry), ExitKind.CONSUME, Fx.stack("diamond x3").hash16(), 3, 5_000L + txn);
    }

    private static Ledger intentThenDone() {
        return new Ledger(List.of(intent(0xAB, 3), new LedgerRecord.Done(Fx.id(0xAB))));
    }

    @Test
    void ledgerEmpty() throws IOException {
        byte[] bytes = LedgerCodec.encode(Ledger.EMPTY);
        check("ledger-v1-empty", "header only", bytes);
        assertThat(LedgerCodec.read(bytes)).isEqualTo(new LedgerRead.Valid(Ledger.EMPTY, 6, 0));
    }

    @Test
    void ledgerIntentAndDone() throws IOException {
        byte[] bytes = LedgerCodec.encode(intentThenDone());
        check("ledger-v1-intent-done", "header, INTENT(txn 0xAB, entry 3, CONSUME), DONE(txn 0xAB)", bytes);
        assertThat(LedgerCodec.read(bytes)).isEqualTo(new LedgerRead.Valid(intentThenDone(), bytes.length, 0));
    }

    @Test
    void ledgerTornTailIsNotARecord() throws IOException {
        byte[] whole = LedgerCodec.encode(new Ledger(List.of(intent(0xAB, 3))));
        byte[] second = LedgerCodec.encodeRecord(intent(0xCD, 5));
        byte[] bytes = concat(whole, Arrays.copyOf(second, 20));
        check("ledger-v1-torn-tail", "one INTENT, then the first 20 bytes of a second (a kill mid-append)", bytes);
        assertThat(LedgerCodec.read(bytes))
                .isEqualTo(new LedgerRead.Valid(new Ledger(List.of(intent(0xAB, 3))), whole.length, 20));
    }

    @Test
    void ledgerLastRecordWithABadCrcIsATornTail() throws IOException {
        byte[] whole = LedgerCodec.encode(new Ledger(List.of(intent(0xAB, 3))));
        byte[] last = LedgerCodec.encodeRecord(intent(0xCD, 5));
        last[last.length - 1] ^= 0x40;
        byte[] bytes = concat(whole, last);
        check("ledger-v1-torn-tail-bad-crc", "one INTENT, then a complete record that fails its CRC at the end", bytes);
        assertThat(LedgerCodec.read(bytes))
                .isEqualTo(new LedgerRead.Valid(new Ledger(List.of(intent(0xAB, 3))), whole.length, last.length));
    }

    @Test
    void ledgerBadRecordBeforeTheEndIsCorruption() throws IOException {
        byte[] header = LedgerCodec.header();
        byte[] first = LedgerCodec.encodeRecord(intent(0xAB, 3));
        first[first.length - 1] ^= 0x40;
        byte[] bytes = concat(header, first, LedgerCodec.encodeRecord(intent(0xCD, 5)));
        check("ledger-v1-corrupt-mid", "a bad CRC in the first of two records: corruption, not a tail", bytes);
        assertThat(LedgerCodec.read(bytes)).isEqualTo(new LedgerRead.Corrupt(6, "record checksum"));
    }

    @Test
    void ledgerNewerVersionIsOpaque() throws IOException {
        byte[] bytes = {'M', 'T', 'R', 'L', 0, 2, 9, 9, 9};
        check("ledger-v1-newer-version", "MTRL, version 2, opaque body: a newer writer's ledger", bytes);
        assertThat(LedgerCodec.read(bytes)).isEqualTo(new LedgerRead.Newer(2));
    }

    @Test
    void ledgerZeroFilledTailIsTorn() throws IOException {
        byte[] whole = LedgerCodec.encode(new Ledger(List.of(intent(0xAB, 3))));
        byte[] bytes = concat(whole, new byte[24]);
        check("ledger-v1-zero-tail", "one INTENT, then 24 zero bytes (a file extended and never filled)", bytes);
        assertThat(LedgerCodec.read(bytes))
                .isEqualTo(new LedgerRead.Valid(new Ledger(List.of(intent(0xAB, 3))), whole.length, 24));
    }

    @Test
    void ledgerTornHeaderIsAFileNotYetBorn() throws IOException {
        byte[] bytes = {'M', 'T'};
        check("ledger-v1-torn-header", "two bytes of the header (a kill while the file was created)", bytes);
        assertThat(LedgerCodec.read(bytes)).isEqualTo(new LedgerRead.Valid(Ledger.EMPTY, 0, 2));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static void roundTrips(byte[] bytes, Account expected) {
        AccountRead read = AccountCodec.decode(bytes);
        assertThat(read).isEqualTo(new AccountRead.Readable(expected));
        assertThat(AccountCodec.encode(((AccountRead.Readable) read).account()))
                .as("decode then encode is byte-identical")
                .isEqualTo(bytes);
    }

    private static byte[] concat(byte[]... parts) {
        int size = Arrays.stream(parts).mapToInt(p -> p.length).sum();
        ByteBuffer out = ByteBuffer.allocate(size);
        Arrays.stream(parts).forEach(out::put);
        return out.array();
    }

    private static void recrc(byte[] account) {
        CRC32 crc = new CRC32();
        crc.update(account, 0, account.length - 4);
        ByteBuffer.wrap(account, account.length - 4, 4).putInt((int) crc.getValue());
    }

    /** Asserts the committed hex file equals {@code actual}; with the regen flag, writes it instead. */
    private static void check(String name, String description, byte[] actual) throws IOException {
        Path file = DIR.resolve(name + ".hex");
        if (GoldenFiles.regenEnabled()) {
            Files.createDirectories(DIR);
            Files.writeString(file, toHex(description, actual), StandardCharsets.UTF_8);
        }
        assertThat(file).as("fixture %s exists (regenerate with -Dmaterione.golden.regen=true)", name).exists();
        assertThat(fromHex(Files.readString(file, StandardCharsets.UTF_8)))
                .as("fixture %s", name)
                .isEqualTo(actual);
    }

    private static String toHex(String description, byte[] bytes) {
        StringBuilder out = new StringBuilder("# ").append(description).append('\n');
        String hex = HexFormat.of().formatHex(bytes);
        for (int i = 0; i < hex.length(); i += 32) {
            out.append(hex, i, Math.min(hex.length(), i + 32)).append('\n');
        }
        return out.toString();
    }

    private static byte[] fromHex(String text) {
        StringBuilder hex = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (!line.startsWith("#")) {
                hex.append(line.strip());
            }
        }
        return HexFormat.of().parseHex(hex);
    }
}
