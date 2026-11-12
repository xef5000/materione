package dev.materione.plugin.escrow.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The read-back of tier 3 against the states the server's replace can leave (gui-input-durability.md §3 F2, §11 P6).
 * Time is a recorded sequence of delays and a hook between attempts, so "the server finishes the replace during the
 * second wait" is a line of the test, not a race.
 */
class EscrowReadBackTest {

    private final UUID player = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

    @TempDir
    Path playerdata;

    @TempDir
    Path second;

    private final List<Long> delays = new ArrayList<>();
    private IntConsumer between = n -> { };

    private EscrowReadBack reader(Path... dirs) {
        return new EscrowReadBack(() -> List.of(dirs), Runnable::run, PlayerDatFiles.KEY, (millis, task) -> {
            delays.add(millis);
            between.accept(delays.size());
            task.run();
        });
    }

    private ReadBack read(EscrowReadBack reader) {
        return reader.read(player).join();
    }

    @Test
    void readsTheGenOfTheAccountInTheSavedFile() throws IOException {
        PlayerDatFiles.save(playerdata, player, PlayerDatFiles.account(41), 30);

        assertThat(read(reader(playerdata))).isInstanceOfSatisfying(ReadBack.Saved.class,
                saved -> assertThat(saved.gen()).isEqualTo(41));
        assertThat(delays).as("no retry was needed").isEmpty();
    }

    @Test
    void aGenAboveTheSignBitIsStillAGen() throws IOException {
        PlayerDatFiles.save(playerdata, player, PlayerDatFiles.account(-1L), 1);

        assertThat(read(reader(playerdata))).isInstanceOfSatisfying(ReadBack.Saved.class,
                saved -> assertThat(saved.gen()).isEqualTo(-1L));
    }

    @Test
    void aSnapshotWithoutAnAccountIsNoAccount() throws IOException {
        PlayerDatFiles.save(playerdata, player, null, 2);

        assertThat(read(reader(playerdata))).isInstanceOf(ReadBack.NoAccount.class);
    }

    @Test
    void anUnreadableAccountIsNoAccountNotAGen() throws IOException {
        byte[] damaged = PlayerDatFiles.account(9);
        damaged[damaged.length - 1] ^= 1; // the CRC no longer matches
        PlayerDatFiles.save(playerdata, player, damaged, 2);

        assertThat(read(reader(playerdata))).isInstanceOfSatisfying(ReadBack.NoAccount.class,
                none -> assertThat(none.detail()).contains("BAD_CRC"));
    }

    @Test
    void noFileAtAllIsUnavailableAfterTheWholeRetryScheduleNeverAnAcknowledgment() {
        assertThat(read(reader(playerdata))).isInstanceOf(ReadBack.Unavailable.class);
        assertThat(delays).containsExactly(20L, 50L, 100L, 250L);
    }

    @Test
    void aReplaceInProgressIsWaitedOutAndTheNewSnapshotIsRead() throws IOException {
        // the window between the server's two renames: .dat is gone, .dat_old is the previous snapshot
        PlayerDatFiles.save(playerdata, player, PlayerDatFiles.account(3), 1);
        Path temp = Files.createTempFile(playerdata, "next", ".dat");
        Files.write(temp, PlayerDatFiles.gzip(PlayerDatFiles.nbt(PlayerDatFiles.account(4), 1, true)));
        Files.move(playerdata.resolve(player + ".dat"), playerdata.resolve(player + ".dat_old"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        between = n -> {
            if (n == 2) {
                try {
                    Files.move(temp, playerdata.resolve(player + ".dat"));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        };

        assertThat(read(reader(playerdata))).isInstanceOfSatisfying(ReadBack.Saved.class,
                saved -> assertThat(saved.gen()).isEqualTo(4));
        assertThat(delays).containsExactly(20L, 50L);
    }

    @Test
    void aFileThatStaysMissingFallsBackToDatOldExactlyAsTheServersLoadDoes() throws IOException {
        PlayerDatFiles.save(playerdata, player, PlayerDatFiles.account(3), 1);
        Files.move(playerdata.resolve(player + ".dat"), playerdata.resolve(player + ".dat_old"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertThat(read(reader(playerdata))).isInstanceOfSatisfying(ReadBack.Saved.class, saved -> {
            assertThat(saved.gen()).isEqualTo(3);
            assertThat(saved.file().getFileName().toString()).endsWith(".dat_old");
        });
    }

    @Test
    void aDatThatCannotBeReadIsNotReplacedByAnOlderDatOld() throws IOException {
        PlayerDatFiles.save(playerdata, player, PlayerDatFiles.account(3), 1);
        PlayerDatFiles.save(playerdata, player, PlayerDatFiles.account(4), 1); // .dat_old now holds gen 3
        Files.write(playerdata.resolve(player + ".dat"), new byte[] {0, 1, 2}); // present, not a gzip stream

        assertThat(read(reader(playerdata))).isInstanceOf(ReadBack.Unavailable.class);
    }

    @Test
    void aTornDatThatHealsBetweenAttemptsIsReadOnceItHas() throws IOException {
        Path dat = playerdata.resolve(player + ".dat");
        byte[] whole = PlayerDatFiles.gzip(PlayerDatFiles.nbt(PlayerDatFiles.account(12), 40, true));
        Files.write(dat, java.util.Arrays.copyOf(whole, whole.length / 2));
        between = n -> {
            if (n == 3) {
                try {
                    Files.write(dat, whole);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        };

        assertThat(read(reader(playerdata))).isInstanceOfSatisfying(ReadBack.Saved.class,
                saved -> assertThat(saved.gen()).isEqualTo(12));
    }

    @Test
    void whenBothLayoutsHoldTheFileTheNewestIsRead() throws IOException {
        PlayerDatFiles.save(playerdata, player, PlayerDatFiles.account(5), 1);
        PlayerDatFiles.save(second, player, PlayerDatFiles.account(8), 1);
        Files.setLastModifiedTime(playerdata.resolve(player + ".dat"), FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(second.resolve(player + ".dat"), FileTime.fromMillis(5_000));

        assertThat(read(reader(playerdata, second))).isInstanceOfSatisfying(ReadBack.Saved.class,
                saved -> assertThat(saved.gen()).isEqualTo(8));
        Files.setLastModifiedTime(playerdata.resolve(player + ".dat"), FileTime.fromMillis(9_000));
        assertThat(read(reader(playerdata, second))).isInstanceOfSatisfying(ReadBack.Saved.class,
                saved -> assertThat(saved.gen()).isEqualTo(5));
    }

    @Test
    void aMissingLayoutDirectoryIsNotAnError() throws IOException {
        PlayerDatFiles.save(second, player, PlayerDatFiles.account(2), 1);

        assertThat(read(reader(playerdata.resolve("absent"), second))).isInstanceOfSatisfying(ReadBack.Saved.class,
                saved -> assertThat(saved.gen()).isEqualTo(2));
    }

    @Test
    void pollingFindsTheQuitSaveWhenItLands() throws IOException {
        PlayerDatFiles.save(playerdata, player, PlayerDatFiles.account(3), 1);
        between = n -> {
            if (n == 3) {
                try {
                    PlayerDatFiles.save(playerdata, player, PlayerDatFiles.account(6), 1);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        };

        OptionalLong reached = reader(playerdata).awaitAtLeast(player, 5).join();

        assertThat(reached).hasValue(6);
        assertThat(delays).as("three polls, each after its own delay").containsExactly(1_000L, 3_000L, 10_000L);
    }

    @Test
    void pollingGivesUpWithNothingWhenTheGenNeverArrives() throws IOException {
        PlayerDatFiles.save(playerdata, player, PlayerDatFiles.account(3), 1);

        assertThat(reader(playerdata).awaitAtLeast(player, 4).join()).isEmpty();
        assertThat(delays).containsExactly(1_000L, 3_000L, 10_000L, 30_000L);
    }

    @Test
    void pollingComparesTheGenUnsigned() throws IOException {
        PlayerDatFiles.save(playerdata, player, PlayerDatFiles.account(-2L), 1); // 2^64 - 2

        assertThat(reader(playerdata).awaitAtLeast(player, 5).join()).hasValue(-2L);
    }
}
