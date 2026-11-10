package dev.materione.plugin.escrow.io;

import static dev.materione.plugin.escrow.io.LedgerRigTest.done;
import static dev.materione.plugin.escrow.io.LedgerRigTest.id;
import static dev.materione.plugin.escrow.io.LedgerRigTest.intent;
import static dev.materione.plugin.escrow.io.LedgerRigTest.player;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.LedgerCodec;
import dev.materione.plugin.escrow.model.LedgerRead;
import dev.materione.plugin.escrow.model.LedgerRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Row L: what the writer does to the file (gui-input-durability.md §5.3, §5.10 K17/K18): torn tails, corruption,
 * quarantine, compaction and its atomicity, fsync, the preload and the diagnostics.
 */
class EscrowLedgerFilesTest {

    private static final long DAY = Duration.ofDays(1).toMillis();

    @TempDir
    Path tmp;

    private LedgerRigTest rig(LedgerOptions options) {
        return new LedgerRigTest(tmp.resolve("escrow"), 4, options);
    }

    private static byte[] bytesOf(LedgerRecord... records) {
        return LedgerCodec.encode(new Ledger(List.of(records)));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ---- preload ------------------------------------------------------------------------------------------------

    @Test
    void aPlayerWithNoFileHasAnEmptyLedgerAndNothingIsCreated() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            LedgerState state = rig.store.preload(player(1)).get(5, TimeUnit.SECONDS);
            assertThat(state).isEqualTo(new LedgerState.Ready(Ledger.EMPTY));
            assertThat(rig.dir).doesNotExist();
            assertThat(rig.store.preloaded(player(1))).contains(state);
        }
    }

    @Test
    void preloadReadsTheRecordsIntoMemoryAndNeverChangesTheFile() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            long now = rig.now.get();
            byte[] disk = concat(bytesOf(intent(1, now), done(1), intent(2, now)), new byte[] {0, 0, 0});
            LedgerRigTest.write(rig.file(player(1)), disk);
            LedgerState.Ready ready = (LedgerState.Ready) rig.store.preload(player(1)).get(5, TimeUnit.SECONDS);
            assertThat(ready.ledger().records()).containsExactly(intent(1, now), done(1), intent(2, now));
            assertThat(ready.ledger().hasIntent(id(1001))).isTrue();
            assertThat(Files.readAllBytes(rig.file(player(1)))).as("a read never mutates, torn tail included")
                    .isEqualTo(disk);
        }
    }

    // ---- torn tail (K17) ----------------------------------------------------------------------------------------

    @Test
    void aTornTailIsIgnoredOnReadAndCutByTheNextAppend() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            byte[] whole = bytesOf(intent(1, 5L));
            byte[] next = LedgerCodec.encodeRecord(done(1));
            for (int cut : new int[] {1, 5, 8, next.length - 1}) { // mid length, mid CRC, no body, one byte short
                UUID who = player(10 + cut);
                LedgerRigTest.write(rig.file(who), concat(whole, Arrays.copyOf(next, cut)));
                LedgerState.Ready ready = (LedgerState.Ready) rig.store.preload(who).get(5, TimeUnit.SECONDS);
                assertThat(ready.ledger().records()).as("cut %d: the partial record is not a record", cut)
                        .containsExactly(intent(1, 5L));
                AppendResult result = rig.store.append(who, done(1)).get(5, TimeUnit.SECONDS);
                assertThat(result).isInstanceOf(AppendResult.Durable.class);
                assertThat(Files.readAllBytes(rig.file(who))).as("cut %d: truncated, then appended", cut)
                        .isEqualTo(bytesOf(intent(1, 5L), done(1)));
            }
        }
    }

    @Test
    void aFileCutShortInItsHeaderIsStartedOverByTheNextAppend() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            LedgerRigTest.write(rig.file(player(1)), Arrays.copyOf(LedgerCodec.header(), 3));
            assertThat(rig.store.preload(player(1)).get(5, TimeUnit.SECONDS))
                    .isEqualTo(new LedgerState.Ready(Ledger.EMPTY));
            rig.store.append(player(1), intent(1, 5L)).get(5, TimeUnit.SECONDS);
            assertThat(Files.readAllBytes(rig.file(player(1)))).isEqualTo(bytesOf(intent(1, 5L)));
        }
    }

    @Test
    void theFirstAppendOfANewPlayerWritesTheHeaderThenTheRecord() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            rig.store.append(player(1), intent(1, 5L)).get(5, TimeUnit.SECONDS);
            rig.store.append(player(1), done(1)).get(5, TimeUnit.SECONDS);
            assertThat(Files.readAllBytes(rig.file(player(1)))).isEqualTo(bytesOf(intent(1, 5L), done(1)));
            assertThat(rig.store.preloaded(player(1))).containsInstanceOf(LedgerState.Ready.class);
        }
    }

    @Test
    void aFileChangedBehindTheWritersBackIsReadAgainBeforeTheAppend() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            rig.store.append(player(1), intent(1, 5L)).get(5, TimeUnit.SECONDS);
            // an admin (or a restored backup) rewrote the file while the player was loaded
            LedgerRigTest.write(rig.file(player(1)), bytesOf(intent(1, 5L), intent(2, 5L)));
            AppendResult result = rig.store.append(player(1), done(2)).get(5, TimeUnit.SECONDS);
            assertThat(result).isInstanceOfSatisfying(AppendResult.Durable.class, durable ->
                    assertThat(durable.ledger().records()).containsExactly(intent(1, 5L), intent(2, 5L), done(2)));
            assertThat(Files.readAllBytes(rig.file(player(1))))
                    .isEqualTo(bytesOf(intent(1, 5L), intent(2, 5L), done(2)));
        }
    }

    // ---- corruption (K18) ---------------------------------------------------------------------------------------

    @Test
    void aCorruptLedgerIsMovedAsideNeverDeletedAndTheServerIsToldOnce() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            byte[] disk = bytesOf(intent(1, 5L), intent(2, 5L), intent(3, 5L));
            disk[LedgerCodec.HEADER_LENGTH + 8 + 3] ^= 0x40; // a flipped bit inside the first record
            LedgerRigTest.write(rig.file(player(1)), disk);
            LedgerState state = rig.store.preload(player(1)).get(5, TimeUnit.SECONDS);
            assertThat(state).isInstanceOfSatisfying(LedgerState.Unrecoverable.class, unrecoverable -> {
                assertThat(unrecoverable.why()).isEqualTo(LedgerState.Why.CORRUPT);
                assertThat(unrecoverable.file()).contains(".ledger.corrupt-" + rig.now.get());
                assertThat(unrecoverable.detail()).contains("renamed aside");
            });
            assertThat(rig.file(player(1))).doesNotExist();
            Path aside = Path.of(((LedgerState.Unrecoverable) state).file());
            assertThat(Files.readAllBytes(aside)).as("the bytes are preserved untouched").isEqualTo(disk);
            assertThat(rig.diagnostics).hasSize(1).allSatisfy(d -> {
                assertThat(d.code()).isEqualTo(DiagnosticCodes.ESCROW_STATE_UNREADABLE);
                assertThat(d.message()).contains(player(1).toString()).contains("CORRUPT");
            });
            // the player is refused, and no fresh ledger appears beside the quarantine
            AppendResult refused = rig.store.append(player(1), intent(9, 5L)).get(5, TimeUnit.SECONDS);
            assertThat(refused).isInstanceOfSatisfying(AppendResult.Refused.class,
                    r -> assertThat(r.reason()).isEqualTo(AppendResult.Reason.UNRECOVERABLE));
            assertThat(rig.file(player(1))).doesNotExist();
        }
    }

    @Test
    void aCorruptMidFileRecordWithValidRecordsAfterItIsCorruptionNotATail() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            byte[] disk = bytesOf(intent(1, 5L), intent(2, 5L));
            int second = LedgerCodec.HEADER_LENGTH + LedgerCodec.encodeRecord(intent(1, 5L)).length;
            disk[second + 8 + 2] ^= 0x01;
            // the damaged record is the last: that is a torn tail by the model's rule, not corruption
            LedgerRigTest.write(rig.file(player(1)), disk);
            assertThat(rig.store.preload(player(1)).get(5, TimeUnit.SECONDS)).isInstanceOf(LedgerState.Ready.class);
            // damaged first, valid after: corruption
            byte[] damagedFirst = bytesOf(intent(1, 5L), intent(2, 5L));
            damagedFirst[LedgerCodec.HEADER_LENGTH + 8 + 2] ^= 0x01;
            LedgerRigTest.write(rig.file(player(2)), damagedFirst);
            assertThat(rig.store.preload(player(2)).get(5, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(LedgerState.Unrecoverable.class,
                            u -> assertThat(u.why()).isEqualTo(LedgerState.Why.CORRUPT));
        }
    }

    @Test
    void aQuarantineStaysUnresolvedAcrossARestartUntilTheAdminMovesItAway() throws Exception {
        byte[] garbage = "not a ledger at all".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path quarantined;
        try (LedgerRigTest first = rig(LedgerOptions.DEFAULTS)) {
            LedgerRigTest.write(first.file(player(1)), garbage);
            first.store.preload(player(1)).get(5, TimeUnit.SECONDS);
            quarantined = Path.of(((LedgerState.Unrecoverable) first.store.preloaded(player(1)).orElseThrow()).file());
            assertThat(quarantined).exists();
        }
        // "restart": a new store over the same directory. The ledger file is gone, but a fresh empty ledger would
        // forget every INTENT: the player must stay refused, not silently start over.
        try (LedgerRigTest second = rig(LedgerOptions.DEFAULTS)) {
            LedgerState state = second.store.preload(player(1)).get(5, TimeUnit.SECONDS);
            assertThat(state).isInstanceOfSatisfying(LedgerState.Unrecoverable.class, u -> {
                assertThat(u.why()).isEqualTo(LedgerState.Why.QUARANTINED);
                assertThat(u.file()).isEqualTo(quarantined.getFileName().toString());
            });
            assertThat(second.diagnostics).hasSize(1);
            assertThat(second.store.append(player(1), intent(1, 5L)).get(5, TimeUnit.SECONDS))
                    .isInstanceOf(AppendResult.Refused.class);
            // other players are untouched by it
            assertThat(second.store.preload(player(2)).get(5, TimeUnit.SECONDS)).isInstanceOf(LedgerState.Ready.class);
        }
        // the admin resolved it by hand: the quarantine file is moved out of the directory
        Files.move(quarantined, tmp.resolve("resolved.bin"));
        try (LedgerRigTest third = rig(LedgerOptions.DEFAULTS)) {
            assertThat(third.store.preload(player(1)).get(5, TimeUnit.SECONDS))
                    .isEqualTo(new LedgerState.Ready(Ledger.EMPTY));
        }
    }

    @Test
    void aNewerVersionIsOpaqueUntouchedAndNeverAppendedTo() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            byte[] newer = ByteBuffer.allocate(LedgerCodec.HEADER_LENGTH + 4)
                    .put(LedgerCodec.MAGIC).putShort((short) (LedgerCodec.VERSION + 1)).putInt(0xCAFEBABE).array();
            LedgerRigTest.write(rig.file(player(1)), newer);
            assertThat(rig.store.preload(player(1)).get(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(
                    LedgerState.Unrecoverable.class, u -> assertThat(u.why()).isEqualTo(LedgerState.Why.NEWER_VERSION));
            assertThat(rig.store.append(player(1), intent(1, 5L)).get(5, TimeUnit.SECONDS))
                    .isInstanceOf(AppendResult.Refused.class);
            assertThat(Files.readAllBytes(rig.file(player(1)))).isEqualTo(newer);
            try (Stream<Path> files = Files.list(rig.dir)) {
                assertThat(files.map(p -> p.getFileName().toString())).containsExactly(player(1) + ".ledger");
            }
            assertThat(rig.diagnostics).singleElement().satisfies(d ->
                    assertThat(d.code()).isEqualTo(DiagnosticCodes.ESCROW_STATE_UNREADABLE));
        }
    }

    @Test
    void anUnreadableFileIsRetriedTheNextTimeNotRememberedAsFinal() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            // the "file" is a directory: reading it is an IO error, not corruption
            Files.createDirectories(rig.file(player(1)));
            LedgerState state = rig.store.preload(player(1)).get(5, TimeUnit.SECONDS);
            assertThat(state).isInstanceOfSatisfying(LedgerState.Unrecoverable.class,
                    u -> assertThat(u.why()).isEqualTo(LedgerState.Why.IO_ERROR));
            Files.delete(rig.file(player(1)));
            assertThat(rig.store.preload(player(1)).get(5, TimeUnit.SECONDS))
                    .isEqualTo(new LedgerState.Ready(Ledger.EMPTY));
        }
    }

    // ---- compaction ---------------------------------------------------------------------------------------------

    @Test
    void preloadCompactsAcknowledgedExitsOlderThanTheRetention() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            long now = rig.now.get();
            LedgerRecord oldPair = intent(1, now - 31 * DAY);
            LedgerRecord recentPair = intent(2, now - 2 * DAY);
            LedgerRecord oldUndone = intent(3, now - 90 * DAY);
            LedgerRigTest.write(rig.file(player(1)), bytesOf(oldPair, done(1), recentPair, done(2), oldUndone));
            LedgerState.Ready ready = (LedgerState.Ready) rig.store.preload(player(1)).get(5, TimeUnit.SECONDS);
            assertThat(ready.ledger().records()).containsExactly(recentPair, done(2), oldUndone);
            assertThat(Files.readAllBytes(rig.file(player(1)))).isEqualTo(bytesOf(recentPair, done(2), oldUndone));
            // G2 still holds for what was kept, and the dropped pair is simply no longer remembered
            assertThat(ready.ledger().hasIntent(id(1003))).isTrue();
            assertThat(ready.ledger().hasIntent(id(1001))).isFalse();
            assertThat(rig.dir.resolve(player(1) + ".ledger.tmp")).doesNotExist();
        }
    }

    @Test
    void nothingDroppableMeansNoRewrite() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            long now = rig.now.get();
            byte[] disk = concat(bytesOf(intent(1, now - 5 * DAY), done(1)), new byte[] {0, 0});
            LedgerRigTest.write(rig.file(player(1)), disk);
            assertThat(rig.store.compact(player(1)).get(5, TimeUnit.SECONDS).outcome())
                    .isEqualTo(CompactResult.Outcome.NOTHING_TO_DO);
            assertThat(Files.readAllBytes(rig.file(player(1)))).isEqualTo(disk);
        }
    }

    @Test
    void compactionToNothingLeavesAValidEmptyLedgerAndLaterAppendsWork() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            long now = rig.now.get();
            LedgerRigTest.write(rig.file(player(1)), bytesOf(intent(1, now - 40 * DAY), done(1)));
            CompactResult result = rig.store.compact(player(1)).get(5, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(CompactResult.Outcome.COMPACTED);
            assertThat(result.dropped()).isEqualTo(2);
            assertThat(Files.readAllBytes(rig.file(player(1)))).isEqualTo(LedgerCodec.header());
            rig.store.append(player(1), intent(5, now)).get(5, TimeUnit.SECONDS);
            assertThat(rig.ledgerOnDisk(player(1)).records()).containsExactly(intent(5, now));
        }
    }

    @Test
    void aFailureBeforeTheRenameLeavesTheOldFileWholeAndALaterCompactionRecovers() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            long now = rig.now.get();
            byte[] disk = bytesOf(intent(1, now - 40 * DAY), done(1), intent(2, now));
            LedgerRigTest.write(rig.file(player(1)), disk);
            // "kill" between the finished temporary file and the rename
            rig.hooks.beforeCompactionMove = file -> {
                throw new IllegalStateException("killed before the rename");
            };
            LedgerState.Ready ready = (LedgerState.Ready) rig.store.preload(player(1)).get(5, TimeUnit.SECONDS);
            assertThat(ready.ledger().records()).as("the failed compaction changed nothing in memory")
                    .containsExactly(intent(1, now - 40 * DAY), done(1), intent(2, now));
            assertThat(Files.readAllBytes(rig.file(player(1)))).as("the old file is whole").isEqualTo(disk);
            assertThat(rig.dir.resolve(player(1) + ".ledger.tmp")).as("the stray temp is the only residue").exists();
            assertThat(rig.log).anyMatch(line -> line.contains("compaction") && line.contains("failed"));
            // a restart reads the old file as the truth; the stray temp is never read
            try (LedgerRigTest restarted = rig(LedgerOptions.DEFAULTS)) {
                assertThat(restarted.store.preload(player(1)).get(5, TimeUnit.SECONDS)).isInstanceOf(LedgerState.Ready.class);
                assertThat(Files.readAllBytes(restarted.file(player(1)))).isEqualTo(bytesOf(intent(2, now)));
            }
        }
    }

    @Test
    void aStrayPartialTempFileIsOverwrittenAndNeverReadAsTheLedger() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            long now = rig.now.get();
            byte[] disk = bytesOf(intent(1, now - 40 * DAY), done(1), intent(2, now));
            LedgerRigTest.write(rig.file(player(1)), disk);
            Files.write(rig.dir.resolve(player(1) + ".ledger.tmp"), new byte[] {1, 2, 3}); // a kill mid-rewrite
            assertThat(rig.store.preload(player(1)).get(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(
                    LedgerState.Ready.class, ready -> assertThat(ready.ledger().records()).containsExactly(intent(2, now)));
            assertThat(Files.readAllBytes(rig.file(player(1)))).isEqualTo(bytesOf(intent(2, now)));
            assertThat(rig.dir.resolve(player(1) + ".ledger.tmp")).doesNotExist();
        }
    }

    // ---- fsync --------------------------------------------------------------------------------------------------

    @Test
    void fsyncForcesEveryAppendAndTheOptionSwitchesItOff() throws Exception {
        for (boolean fsync : new boolean[] {true, false}) {
            AtomicInteger forced = new AtomicInteger();
            Path escrow = tmp.resolve("escrow-" + fsync);
            try (LedgerRigTest rig = new LedgerRigTest(escrow, 2, LedgerOptions.DEFAULTS.withFsync(fsync))) {
                rig.hooks.afterForce = forced::incrementAndGet;
                for (int n = 0; n < 5; n++) {
                    assertThat(rig.store.append(player(1), intent(n, 1L)).get(5, TimeUnit.SECONDS))
                            .isInstanceOf(AppendResult.Durable.class);
                }
                assertThat(forced.get()).as("fsync=%s", fsync).isEqualTo(fsync ? 5 : 0);
                assertThat(rig.ledgerOnDisk(player(1)).records()).hasSize(5);
            }
        }
    }

    // ---- failure and diagnostics --------------------------------------------------------------------------------

    @Test
    void aFailedAppendIsRefusedReportedOncePerMinuteAndHealsWhenTheDiskDoes() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            rig.hooks.beforeAppend = who -> {
                throw new java.io.UncheckedIOException(new IOException("No space left on device"));
            };
            AppendResult first = rig.store.append(player(1), intent(1, 1L)).get(5, TimeUnit.SECONDS);
            assertThat(first).isInstanceOfSatisfying(AppendResult.Refused.class, r -> {
                assertThat(r.reason()).isEqualTo(AppendResult.Reason.WRITE_FAILED);
                assertThat(r.detail()).contains("No space left");
            });
            rig.store.append(player(1), intent(2, 1L)).get(5, TimeUnit.SECONDS);
            assertThat(rig.diagnostics).as("one report per player per minute").singleElement().satisfies(d -> {
                assertThat(d.code()).isEqualTo(DiagnosticCodes.ESCROW_TRANSFER_REFUSED);
                assertThat(d.message()).contains(player(1).toString());
            });
            rig.now.addAndGet(61_000);
            rig.store.append(player(1), intent(3, 1L)).get(5, TimeUnit.SECONDS);
            assertThat(rig.diagnostics).hasSize(2);
            rig.hooks.beforeAppend = who -> {}; // the disk heals
            AppendResult healed = rig.store.append(player(1), intent(4, 1L)).get(5, TimeUnit.SECONDS);
            assertThat(healed).isInstanceOf(AppendResult.Durable.class);
            assertThat(rig.ledgerOnDisk(player(1)).records()).containsExactly(intent(4, 1L));
        }
    }

    @Test
    void aRecordWhoseForceFailsIsCutBackOffSoRefusedMeansNotRecorded() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            rig.store.append(player(1), intent(1, 1L)).get(5, TimeUnit.SECONDS);
            byte[] before = Files.readAllBytes(rig.file(player(1)));
            // the write returned and the force failed: the record is on its way to disk, but the caller is told no
            rig.hooks.afterWrite = () -> {
                throw new java.io.UncheckedIOException(new IOException("Input/output error"));
            };
            AppendResult refused = rig.store.append(player(1), intent(2, 1L)).get(5, TimeUnit.SECONDS);
            assertThat(refused).isInstanceOfSatisfying(AppendResult.Refused.class,
                    r -> assertThat(r.reason()).isEqualTo(AppendResult.Reason.WRITE_FAILED));
            assertThat(Files.readAllBytes(rig.file(player(1)))).as("no CRC-valid record of a refused append")
                    .isEqualTo(before);
            rig.hooks.afterWrite = () -> {};
            assertThat(rig.store.append(player(1), intent(3, 1L)).get(5, TimeUnit.SECONDS))
                    .isInstanceOf(AppendResult.Durable.class);
            assertThat(rig.ledgerOnDisk(player(1)).records()).containsExactly(intent(1, 1L), intent(3, 1L));
        }
    }

    @Test
    void aTornTailLeftBehindTheWritersBackIsCutByTheNextAppend() throws Exception {
        try (LedgerRigTest rig = rig(LedgerOptions.DEFAULTS)) {
            rig.store.append(player(1), intent(1, 1L)).get(5, TimeUnit.SECONDS);
            // a kill mid-append left half a record behind our back
            byte[] partial = Arrays.copyOf(LedgerCodec.encodeRecord(intent(2, 1L)), 20);
            Files.write(rig.file(player(1)), partial, java.nio.file.StandardOpenOption.APPEND);
            AppendResult result = rig.store.append(player(1), done(1)).get(5, TimeUnit.SECONDS);
            assertThat(result).isInstanceOf(AppendResult.Durable.class);
            LedgerRead.Valid onDisk = rig.onDisk(player(1));
            assertThat(onDisk.tornBytes()).isZero();
            assertThat(onDisk.ledger().records()).containsExactly(intent(1, 1L), done(1));
        }
    }
}
