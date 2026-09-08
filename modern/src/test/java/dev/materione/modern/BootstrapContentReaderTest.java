package dev.materione.modern;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.content.paintings.PaintingDefinition;
import dev.materione.content.sounds.SoundDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bootstrap reads exactly what the pipeline would compile: the fixture
 * sample pack's painting and song must parse standalone, and an invalid
 * pack must be skipped without breaking the rest (D6).
 */
class BootstrapContentReaderTest {

    @Test
    void readsSamplePackPaintingAndSong(@TempDir Path dataDir) throws Exception {
        Path packs = Path.of(System.getProperty("materione.fixtures.root"), "packs");
        var warnings = new ArrayList<String>();
        // stage the fixture packs under the storage layout the bootstrap expects
        Path contentPacks = dataDir.resolve("content").resolve("packs");
        copyTree(packs, contentPacks);
        BootstrapContentReader.RegistryContent content =
                BootstrapContentReader.read(dataDir, warnings::add);
        assertThat(warnings).isEmpty();
        assertThat(content.paintings()).hasSize(1);
        PaintingDefinition painting = content.paintings().get(0);
        assertThat(painting.id().toString()).isEqualTo("sample:sunrise");
        assertThat(painting.assetId()).isEqualTo("sample:sunrise");
        assertThat(painting.placeable()).isTrue();
        assertThat(painting.width()).isEqualTo(2);
        assertThat(painting.height()).isEqualTo(1);
        assertThat(content.songs()).hasSize(1);
        SoundDefinition song = content.songs().get(0);
        assertThat(song.id().toString()).isEqualTo("sample:anthem");
        assertThat(song.isSong()).isTrue();
    }

    @Test
    void manifestLessPacksAreSkipped(@TempDir Path dataDir) throws Exception {
        Path packs = dataDir.resolve("content").resolve("packs");
        // a pack without pack.yml (the compiler skips it too — silently) and
        // a valid one: the broken pack must contribute nothing, the valid one
        // must still register
        Files.createDirectories(packs.resolve("broken").resolve("paintings"));
        Files.writeString(packs.resolve("broken").resolve("paintings").resolve("x.yml"),
                "x:\n  texture: sunrise\n");
        Path valid = packs.resolve("sample");
        copyTree(Path.of(System.getProperty("materione.fixtures.root"), "packs", "sample"), valid);
        var warnings = new ArrayList<String>();
        BootstrapContentReader.RegistryContent content =
                BootstrapContentReader.read(dataDir, warnings::add);
        assertThat(content.paintings()).hasSize(1);
        assertThat(content.paintings().get(0).id().toString()).isEqualTo("sample:sunrise");
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (var walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
            }
        }
    }
}
