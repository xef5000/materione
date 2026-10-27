package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.pipeline.ReloadScope;
import dev.materione.content.paintings.PaintingType;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.pipeline.PipelineRunner;
import dev.materione.pack.allocation.AllocationTable;
import dev.materione.pack.compile.PackCompiler;
import dev.materione.platform.text.MaterioneText;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Painting titles on the floor (datapack) path, compiled the way the plugin
 * wires it: the sample pack's formatted panorama title and author become text
 * components through MaterioneText (plugin §6.1), plain titles stay strings.
 */
class PaintingTextCompileTest {

    @Test
    void formattedPaintingTextCompilesToTextComponents(@TempDir Path tempDir) throws IOException {
        var diagnostics = new DiagnosticsBusService(64);
        var quarantine = new QuarantineTracker();
        var runner = new PipelineRunner(diagnostics, quarantine);
        runner.request(new ReloadScope.Everything(), GoldenFiles.fixturesRoot().resolve("packs"));
        var sample = runner.lastPacks().stream().filter(pack -> pack.id().equals("sample")).toList();

        var outcome = new PackCompiler(MaterioneText::componentJson).compile(
                sample,
                List.of(new PaintingType()),
                AllocationTable.load(tempDir.resolve("storage/allocation.bin")),
                tempDir.resolve("generated"),
                diagnostics,
                quarantine,
                -1);

        try (var datapack = new ZipFile(outcome.stagedDatapackZip().toFile())) {
            String panorama = text(datapack, "data/sample/painting_variant/panorama.json");
            // one color per character, ends on the stops; no MiniMessage tags survive
            assertThat(panorama)
                    .contains("\"color\":\"#F9731E\"")
                    .contains("\"color\":\"#FFD27A\"")
                    .contains("\"color\":\"gold\"")
                    .doesNotContain("<gradient")
                    .doesNotContain("&6");
            assertThat(text(datapack, "data/sample/painting_variant/sunrise.json"))
                    .contains("\"title\":\"Sunrise\"")
                    .contains("\"author\":\"Materione\"");
        }
    }

    private static String text(ZipFile zip, String entry) throws IOException {
        return new String(zip.getInputStream(zip.getEntry(entry)).readAllBytes(), StandardCharsets.UTF_8);
    }
}
