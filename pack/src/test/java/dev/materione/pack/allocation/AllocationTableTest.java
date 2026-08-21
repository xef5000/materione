package dev.materione.pack.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The world-safety invariants of the allocation table (master §6.3). */
class AllocationTableTest {

    @Test
    void allocationIsStableAcrossReloads(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("storage/allocation.bin");
        var ruby = NamespacedId.parse("sample:ruby_sword");
        var first = AllocationTable.load(file);
        assertThat(first.allocate(ruby, "custom-model-data")).isEqualTo("1");
        first.flush();

        var reloaded = AllocationTable.load(file);
        assertThat(reloaded.allocationOf(ruby, "custom-model-data")).contains("1");
        // a second ask returns the same value; the counter does not double-spend
        assertThat(reloaded.allocate(ruby, "custom-model-data")).isEqualTo("1");
        var axe = NamespacedId.parse("sample:obsidian_axe");
        assertThat(reloaded.allocate(axe, "custom-model-data")).isEqualTo("2");
    }

    @Test
    void tombstonesKeepValuesBound(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("allocation.bin");
        var ruby = NamespacedId.parse("sample:ruby_sword");
        var table = AllocationTable.load(file);
        table.allocate(ruby, "custom-model-data");
        table.tombstone(ruby, "custom-model-data");
        table.flush();

        var reloaded = AllocationTable.load(file);
        assertThat(reloaded.allocationOf(ruby, "custom-model-data")).isEmpty();
        // the retired value is unrecoverable truth: the same id asks back, it
        // gets its old number — never a shifted assignment (world safety)
        assertThat(reloaded.allocate(ruby, "custom-model-data")).isEqualTo("1");
        assertThat(reloaded.allocate(NamespacedId.parse("sample:next"), "custom-model-data"))
                .isEqualTo("2");
    }

    @Test
    void kindsHaveIndependentValueSpaces(@TempDir Path tempDir) throws IOException {
        var table = AllocationTable.load(tempDir.resolve("allocation.bin"));
        var ruby = NamespacedId.parse("sample:ruby_sword");
        assertThat(table.allocate(ruby, "custom-model-data")).isEqualTo("1");
        assertThat(table.allocate(ruby, "glyph-codepoint")).isEqualTo("1");
        table.flush();
    }

    @Test
    void missingFileIsANotCorruptFreshTable(@TempDir Path tempDir) {
        var table = AllocationTable.load(tempDir.resolve("absent.bin"));
        assertThat(table.isCorrupt()).isFalse();
        assertThat(table.liveCount()).isZero();
    }

    @Test
    void garbageFileIsCorruptNotFatal(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("allocation.bin");
        Files.write(file, new byte[] {1, 2, 3, 4, 5});
        var table = AllocationTable.load(file);
        assertThat(table.isCorrupt()).isTrue();
        // doctor offers the audited rebuild; the table never guesses values
        assertThat(table.liveCount()).isZero();
    }

    @Test
    void unflushedAllocationsSurviveOnlyAfterFlush(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("allocation.bin");
        var table = AllocationTable.load(file);
        table.allocate(NamespacedId.parse("sample:x"), "custom-model-data");
        // reload before flush sees nothing — the compiler must flush first
        assertThat(AllocationTable.load(file).liveCount()).isZero();
        table.flush();
        assertThat(AllocationTable.load(file).liveCount()).isOne();
    }
}
