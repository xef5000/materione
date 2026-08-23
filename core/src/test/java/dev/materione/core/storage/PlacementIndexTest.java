package dev.materione.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.core.storage.PlacementIndex.Position;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The disposable index: append/replay discipline, compaction, corrupt-line tolerance. */
class PlacementIndexTest {

    @TempDir
    Path temp;

    @Test
    void addAndRemoveRoundTripThroughTheLog() throws Exception {
        Path file = temp.resolve("index").resolve("placements.jsonl");
        try (PlacementIndex index = PlacementIndex.load(file)) {
            assertThat(index.size()).isZero();
            index.add(new Position("world", 1, 64, 2), "sample:lamp");
            index.add(new Position("world", -3, 70, 15), "sample:crate");
            index.remove(new Position("world", 1, 64, 2));
            assertThat(index.size()).isEqualTo(1);
        }
        try (PlacementIndex reloaded = PlacementIndex.load(file)) {
            assertThat(reloaded.size()).isEqualTo(1);
            assertThat(reloaded.at(new Position("world", -3, 70, 15))).isEqualTo("sample:crate");
        }
    }

    @Test
    void compactionRewritesTheFileToTheLiveSet() throws Exception {
        Path file = temp.resolve("placements.jsonl");
        try (PlacementIndex index = PlacementIndex.load(file)) {
            for (int i = 0; i < 25; i++) {
                index.add(new Position("w", i, i, i), "sample:x" + i);
            }
            for (int i = 0; i < 20; i++) {
                index.remove(new Position("w", i, i, i));
            }
            assertThat(index.size()).isEqualTo(5);
        }
        // boot-time compaction: the rewrite keeps only the live five
        try (PlacementIndex reloaded = PlacementIndex.load(file)) {
            assertThat(reloaded.size()).isEqualTo(5);
            String content = Files.readString(file);
            long lines = content.lines().filter(line -> !line.isBlank()).count();
            assertThat(lines).isEqualTo(5L);
        }
    }

    @Test
    void corruptLinesAreSkippedOnReplay() throws Exception {
        Path file = temp.resolve("placements.jsonl");
        PlacementIndex index = PlacementIndex.load(file);
        index.add(new Position("w", 1, 2, 3), "sample:a");
        index.close();
        // append garbage behind the good record — a half-written tail must not kill the index
        try (var out = Files.newBufferedWriter(file, java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND)) {
            out.write("{\"op\":\"add\",\"world\":\n");
            out.write("not json at all\n");
            out.write("{\"op\":\"add\",\"world\":\"w\",\"x\":9,\"y\":9,\"z\":9,\"block\":\"sample:b\"\n");
        }
        try (PlacementIndex reloaded = PlacementIndex.load(file)) {
            assertThat(reloaded.at(new Position("w", 1, 2, 3))).isEqualTo("sample:a");
            assertThat(reloaded.size()).isEqualTo(1); // both corrupt lines dropped by compaction
        }
    }

    @Test
    void removingUnknownPositionsIsANoOp() throws Exception {
        Path file = temp.resolve("placements.jsonl");
        PlacementIndex index = PlacementIndex.load(file);
        index.add(new Position("w", 0, 0, 0), "sample:a");
        index.remove(new Position("elsewhere", 4, 4, 4));
        index.remove(new Position("w", 0, 0, 0)); // real removal
        index.remove(new Position("w", 0, 0, 0)); // already gone
        assertThat(index.size()).isZero();
        index.close();
        // no del line for unknown positions; compaction leaves an empty live set
        try (PlacementIndex reloaded = PlacementIndex.load(file)) {
            assertThat(reloaded.size()).isZero();
        }
    }
}
