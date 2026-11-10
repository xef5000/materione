package dev.materione.plugin.escrow.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the codecs do at their edges (gui-input-durability.md §5.3, §14 Q7). */
class CodecEdgeCasesTest {

    @Test
    void anAccountAtTheEntryCapEncodesAndOneMoreIsRefusedWithoutAChange() {
        Account atCap = Account.EMPTY;
        for (int i = 1; i <= AccountCodec.MAX_ENTRIES; i++) {
            atCap = atCap.added(Fx.held(i, "s" + i));
        }
        assertThat(AccountCodec.decode(AccountCodec.encode(atCap))).isEqualTo(new AccountRead.Readable(atCap));

        var inventory = new Fx.ListInventory(8, "extra");
        Step step = EscrowTransfer.moveIn(atCap, inventory, Fx.held(99, "extra"));
        assertThat(step).isEqualTo(new Step.Refused(Refusal.ENTRY_CAP));
        assertThat(inventory.stacks).containsExactly(Fx.stack("extra"));
    }

    @Test
    void anAccountOverTheSizeCapIsRefusedAndTheInventoryKeepsTheStack() {
        byte[] big = new byte[AccountCodec.MAX_BYTES];
        var inventory = new Fx.ListInventory(8);
        inventory.stacks.add(StackBytes.of(big));
        Entry entry = Entry.held(Fx.id(1), StackBytes.of(big), "shop", 0, 0);
        assertThat(EscrowTransfer.moveIn(Account.EMPTY, inventory, entry)).isEqualTo(new Step.Refused(Refusal.SIZE_CAP));
        assertThat(inventory.stacks).hasSize(1);
        assertThatThrownBy(() -> AccountCodec.encode(Fx.account(0, entry)))
                .isInstanceOf(AccountCodec.LimitExceededException.class);
    }

    @Test
    void releasingIsNeverRefusedBecauseOfACapTheAccountWasNotWrittenUnder() {
        Account foreign = Account.EMPTY;
        for (int i = 1; i <= AccountCodec.MAX_ENTRIES + 5; i++) {
            foreign = foreign.added(Fx.held(i, "s" + i));
        }
        var inventory = new Fx.ListInventory(100);
        Step step = EscrowTransfer.close(foreign, inventory);
        assertThat(step).isInstanceOf(Step.Applied.class);
        assertThat(inventory.stacks).hasSize(AccountCodec.MAX_ENTRIES + 5);
    }

    @Test
    void everySingleBitFlipOfAnAccountIsUnreadable() {
        byte[] good = AccountCodec.encode(Fx.account(9, Fx.held(1, "diamond x3"), Fx.held(2, "bread").owed()));
        for (int i = 0; i < good.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] bad = good.clone();
                bad[i] ^= (byte) (1 << bit);
                assertThat(AccountCodec.decode(bad)).as("byte %d bit %d", i, bit).isInstanceOf(AccountRead.Unreadable.class);
            }
        }
    }

    @Test
    void everyTruncationOfAnAccountIsUnreadableAndNeverThrows() {
        byte[] good = AccountCodec.encode(Fx.account(9, Fx.held(1, "diamond x3")));
        for (int length = 0; length < good.length; length++) {
            byte[] cut = java.util.Arrays.copyOf(good, length);
            assertThat(AccountCodec.decode(cut)).as("length %d", length).isInstanceOf(AccountRead.Unreadable.class);
        }
    }

    @Test
    void twoEntriesWithTheSameIdAreMalformed() {
        // built by hand: Account itself refuses duplicates
        byte[] one = AccountCodec.encode(Fx.account(1, Fx.held(1, "a")));
        int entryLength = one.length - 12 - 4;
        ByteBuffer out = ByteBuffer.allocate(12 + 2 * entryLength + 4);
        out.put(one, 0, 10).putShort((short) 2).put(one, 12, entryLength).put(one, 12, entryLength);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(out.array(), 0, out.position());
        out.putInt((int) crc.getValue());
        assertThat(AccountCodec.decode(out.array()))
                .isInstanceOfSatisfying(AccountRead.Unreadable.class,
                        unreadable -> assertThat(unreadable.reason()).isEqualTo(AccountRead.Reason.MALFORMED));
    }

    @Test
    void anAbsentValueIsAnAbsentAccount() {
        assertThat(AccountCodec.read(null)).isEqualTo(new AccountRead.Absent());
    }

    @Test
    void aLedgerIsAppendedRecordByRecordToTheSameBytesAsAWholeEncode() {
        var records = List.<LedgerRecord>of(
                new LedgerRecord.Intent(Fx.id(1), Fx.id(2), ExitKind.CONSUME, Fx.stack("x").hash16(), 1, 1),
                new LedgerRecord.Done(Fx.id(1)));
        ByteBuffer appended = ByteBuffer.allocate(1024);
        appended.put(LedgerCodec.header());
        records.forEach(r -> appended.put(LedgerCodec.encodeRecord(r)));
        assertThat(java.util.Arrays.copyOf(appended.array(), appended.position()))
                .isEqualTo(LedgerCodec.encode(new Ledger(records)));
    }

    @Test
    void everyPrefixOfALedgerReadsAsTheRecordsThatCompletedAndNeverThrows() {
        var records = new ArrayList<LedgerRecord>();
        for (int i = 1; i <= 4; i++) {
            records.add(new LedgerRecord.Intent(Fx.id(i), Fx.id(100 + i), ExitKind.CONSUME, Fx.stack("x").hash16(), i, i));
            records.add(new LedgerRecord.Done(Fx.id(i)));
        }
        byte[] file = LedgerCodec.encode(new Ledger(records));
        int[] ends = new int[records.size() + 1];
        ends[0] = LedgerCodec.HEADER_LENGTH;
        for (int i = 0; i < records.size(); i++) {
            ends[i + 1] = ends[i] + LedgerCodec.encodeRecord(records.get(i)).length;
        }
        for (int length = 0; length <= file.length; length++) {
            LedgerRead read = LedgerCodec.read(java.util.Arrays.copyOf(file, length));
            int complete = 0;
            while (complete < records.size() && ends[complete + 1] <= length) {
                complete++;
            }
            assertThat(read).as("prefix %d", length).isInstanceOf(LedgerRead.Valid.class);
            var valid = (LedgerRead.Valid) read;
            assertThat(valid.ledger().records()).as("prefix %d", length).isEqualTo(records.subList(0, complete));
            assertThat(valid.validLength() + valid.tornBytes()).isEqualTo(length);
        }
    }

    @Test
    void aBitFlipInALedgerNeverInventsARecord() {
        var records = List.<LedgerRecord>of(
                new LedgerRecord.Intent(Fx.id(1), Fx.id(2), ExitKind.CONSUME, Fx.stack("x").hash16(), 1, 1),
                new LedgerRecord.Intent(Fx.id(3), Fx.id(4), ExitKind.CONSUME, Fx.stack("y").hash16(), 2, 2));
        byte[] good = LedgerCodec.encode(new Ledger(records));
        for (int i = 0; i < good.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] bad = good.clone();
                bad[i] ^= (byte) (1 << bit);
                LedgerRead read = LedgerCodec.read(bad);
                if (read instanceof LedgerRead.Valid valid) {
                    assertThat(records).as("byte %d bit %d", i, bit).containsAll(valid.ledger().records());
                }
            }
        }
    }

    @Test
    void aGarbledMidFileLengthIsCorruptionNotATailSoLaterIntentsAreNeverSilentlyDropped() {
        byte[] first = LedgerCodec.encodeRecord(
                new LedgerRecord.Intent(Fx.id(1), Fx.id(2), ExitKind.CONSUME, Fx.stack("x").hash16(), 1, 1));
        byte[] second = LedgerCodec.encodeRecord(
                new LedgerRecord.Intent(Fx.id(3), Fx.id(4), ExitKind.CONSUME, Fx.stack("y").hash16(), 2, 2));
        ByteBuffer file = ByteBuffer.allocate(6 + first.length + second.length);
        file.put(LedgerCodec.header()).put(first).put(second);
        file.putInt(6, 0x00FFFFFF); // the first record's length now claims 16 MiB
        assertThat(LedgerCodec.read(file.array())).isInstanceOf(LedgerRead.Corrupt.class);
    }
}
