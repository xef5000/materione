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
        // the corpus only grows — pin the canonical painting by id, not the
        // corpus size
        PaintingDefinition painting = byId(content.paintings(), "sample:sunrise");
        assertThat(painting.assetId()).isEqualTo("sample:sunrise");
        assertThat(painting.placeable()).isTrue();
        assertThat(painting.width()).isEqualTo(2);
        assertThat(painting.height()).isEqualTo(1);
        SoundDefinition song = content.songs().stream()
                .filter(s -> s.id().toString().equals("sample:anthem"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no song sample:anthem in " + content.songs()));
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
        // the broken pack contributes nothing: every parsed painting belongs
        // to the valid sample pack (the corpus may hold several)
        assertThat(content.paintings())
                .allSatisfy(p -> assertThat(p.id().namespace()).isEqualTo("sample"));
        assertThat(byId(content.paintings(), "sample:sunrise").assetId()).isEqualTo("sample:sunrise");
    }

    @Test
    void nestedPaintingsAndSongsRegisterAtBoot(@TempDir Path dataDir) throws Exception {
        // the sample pack with every painting and sound moved one folder down: the
        // bootstrap discovers recursively, exactly as the pipeline does (issue #191)
        Path sample = dataDir.resolve("content").resolve("packs").resolve("sample");
        copyTree(Path.of(System.getProperty("materione.fixtures.root"), "packs", "sample"), sample);
        for (String dir : List.of("paintings", "sounds")) {
            Path source = sample.resolve(dir);
            Path nested = Files.createDirectories(source.resolve("moved").resolve("deeper"));
            List<Path> files;
            try (var listing = Files.list(source)) {
                files = listing.filter(Files::isRegularFile).toList();
            }
            for (Path file : files) {
                Files.move(file, nested.resolve(file.getFileName()));
            }
        }
        var warnings = new ArrayList<String>();
        BootstrapContentReader.RegistryContent content =
                BootstrapContentReader.read(dataDir, warnings::add);

        assertThat(warnings).isEmpty();
        assertThat(byId(content.paintings(), "sample:sunrise").assetId()).isEqualTo("sample:sunrise");
        assertThat(content.songs().stream().map(song -> song.id().toString())).contains("sample:anthem");
    }

    private static PaintingDefinition byId(List<PaintingDefinition> paintings, String id) {
        return paintings.stream()
                .filter(p -> p.id().toString().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no painting " + id + " in " + paintings));
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
