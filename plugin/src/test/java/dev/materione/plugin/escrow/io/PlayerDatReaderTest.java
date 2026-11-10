package dev.materione.plugin.escrow.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reader of the barrier's read-back (gui-input-durability.md §11 P6): finds {@code BukkitValues -> key}, believes
 * nothing it did not read to the end of a valid gzip stream.
 */
class PlayerDatReaderTest {

    @TempDir
    Path dir;

    private Path write(byte[] gzipped) throws IOException {
        Path file = dir.resolve("p.dat");
        Files.write(file, gzipped);
        return file;
    }

    @Test
    void findsTheAccountAmongOtherValuesAndALargeInventory() throws IOException {
        byte[] account = PlayerDatFiles.account(7);
        Path file = write(PlayerDatFiles.gzip(PlayerDatFiles.nbt(account, 200, true)));

        assertThat(PlayerDatReader.readValue(file, PlayerDatFiles.KEY)).hasValueSatisfying(
                found -> assertThat(found).containsExactly(account));
    }

    @Test
    void anAbsentValueOrAbsentPluginDataIsEmptyNotAnError() throws IOException {
        assertThat(PlayerDatReader.readValue(
                write(PlayerDatFiles.gzip(PlayerDatFiles.nbt(null, 3, true))), PlayerDatFiles.KEY)).isEmpty();
        assertThat(PlayerDatReader.readValue(
                write(PlayerDatFiles.gzip(PlayerDatFiles.nbt(null, 3, false))), PlayerDatFiles.KEY)).isEmpty();
    }

    @Test
    void aMissingFileIsNoSuchFile() {
        assertThatThrownBy(() -> PlayerDatReader.readValue(dir.resolve("none.dat"), PlayerDatFiles.KEY))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void aTruncatedFileIsAnErrorNeverAValue() throws IOException {
        byte[] whole = PlayerDatFiles.gzip(PlayerDatFiles.nbt(PlayerDatFiles.account(2), 50, true));
        for (int cut : new int[] {0, 1, 10, whole.length / 2, whole.length - 9, whole.length - 8, whole.length - 1}) {
            Path file = write(Arrays.copyOf(whole, cut));
            assertThatThrownBy(() -> PlayerDatReader.readValue(file, PlayerDatFiles.KEY))
                    .as("cut at %d of %d", cut, whole.length).isInstanceOf(IOException.class);
        }
    }

    @Test
    void aBadGzipTrailerIsAnErrorEvenWhenTheValueWasAlreadyRead() throws IOException {
        byte[] whole = PlayerDatFiles.gzip(PlayerDatFiles.nbt(PlayerDatFiles.account(2), 5, true));
        whole[whole.length - 6] ^= 0x40; // inside the CRC32 trailer: the data is intact, the checksum is not
        Path file = write(whole);
        assertThatThrownBy(() -> PlayerDatReader.readValue(file, PlayerDatFiles.KEY)).isInstanceOf(IOException.class);
    }

    @Test
    void notGzipAndNotNbtAreErrors() throws IOException {
        Path plain = write(PlayerDatFiles.nbt(PlayerDatFiles.account(1), 1, true));
        assertThatThrownBy(() -> PlayerDatReader.readValue(plain, PlayerDatFiles.KEY)).isInstanceOf(IOException.class);
        Path notNbt = write(PlayerDatFiles.gzip(new byte[] {1, 2, 3, 4, 5}));
        assertThatThrownBy(() -> PlayerDatReader.readValue(notNbt, PlayerDatFiles.KEY)).isInstanceOf(IOException.class);
    }

    @Test
    void aRootThatNeverEndsIsAnError() throws IOException {
        byte[] raw = PlayerDatFiles.nbt(PlayerDatFiles.account(1), 1, true);
        byte[] noEnd = Arrays.copyOf(raw, raw.length - 1); // the root compound's end tag removed
        Path file = write(PlayerDatFiles.gzip(noEnd));
        assertThatThrownBy(() -> PlayerDatReader.readValue(file, PlayerDatFiles.KEY)).isInstanceOf(IOException.class);
    }

    @Test
    void anAbsurdArrayLengthIsRefusedWithoutAllocatingIt() throws IOException {
        // root compound { BukkitValues { "materione:gui_escrow": byte array of length 0x7fffffff } }
        var bytes = new java.io.ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(bytes);
        out.writeByte(10);
        out.writeUTF("");
        out.writeByte(10);
        out.writeUTF("BukkitValues");
        out.writeByte(7);
        out.writeUTF(PlayerDatFiles.KEY);
        out.writeInt(Integer.MAX_VALUE);
        out.flush();
        Path file = write(PlayerDatFiles.gzip(bytes.toByteArray()));
        assertThatThrownBy(() -> PlayerDatReader.readValue(file, PlayerDatFiles.KEY)).isInstanceOf(IOException.class);
    }

    @Test
    void absurdNestingIsRefused() throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(bytes);
        out.writeByte(10);
        out.writeUTF("");
        for (int i = 0; i < 2_000; i++) {
            out.writeByte(10);
            out.writeUTF("n");
        }
        out.flush();
        Path file = write(PlayerDatFiles.gzip(bytes.toByteArray()));
        assertThatThrownBy(() -> PlayerDatReader.readValue(file, PlayerDatFiles.KEY)).isInstanceOf(IOException.class);
    }
}
