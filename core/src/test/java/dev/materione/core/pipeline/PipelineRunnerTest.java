package dev.materione.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pipeline skeleton behavior (plugin architecture §4, fail-soft D6): the
 * sample fixture pack loads to a graph; broken files quarantine with
 * diagnostics; one file's failure never blocks the others.
 */
class PipelineRunnerTest {

    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(256);
    private final QuarantineTracker quarantine = new QuarantineTracker();
    private final PipelineRunner runner = new PipelineRunner(diagnostics, quarantine);

    @Test
    void samplePackParsesValidatesAndBuildsAGraph() {
        Path packsRoot = GoldenFiles.fixturesRoot().resolve("packs");
        var result = runner.request(new ReloadScope.Everything(), packsRoot);
        assertThat(result.completed()).isTrue();
        // sample + corpus (the 50-item exit-criteria pack) + world (M2)
        assertThat(result.packsScanned()).isEqualTo(3);
        assertThat(quarantine.size()).isZero();
        // items + templates + recipes: ruby_sword, obsidian_axe, base_item, ruby_sword(recipe)
        assertThat(result.resourcesValid()).isGreaterThanOrEqualTo(4);
        assertThat(runner.lastGraph().node(NamespacedId.parse("corpus:gem_red"))).isPresent();
        assertThat(runner.lastGraph().node(NamespacedId.parse("corpus:scroll_10"))).isPresent();

        var graph = runner.lastGraph();
        assertThat(graph.node(NamespacedId.parse("sample:ruby_sword"))).isPresent();
        assertThat(graph.unresolved()).isEmpty();
        assertThat(graph.edges()).anySatisfy(edge -> {
            assertThat(edge.from().toString()).isEqualTo("sample:ruby_sword");
            assertThat(edge.to().toString()).isEqualTo("sample:base_item");
        });
    }

    @Test
    void brokenSyntaxQuarantinesOnlyItsFile(@TempDir Path packsRoot) throws IOException {
        installFaultPack(packsRoot, "broken", "broken-syntax.yml", "items/broken-syntax.yml");
        var result = runner.request(new ReloadScope.Everything(), packsRoot);
        assertThat(result.completed()).isTrue();
        assertThat(quarantine.size()).isOne();
        assertThat(quarantine.all().get(0).reason().code()).isEqualTo(DiagnosticCodes.CONTENT_PARSE_FAILED);
        assertThat(published(DiagnosticCodes.CONTENT_PARSE_FAILED)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void unresolvedReferenceQuarantinesWithSuggestion(@TempDir Path packsRoot) throws IOException {
        installFaultPack(packsRoot, "faulty", "unresolved-ref.yml", "items/unresolved-ref.yml");
        // a sibling template so the "did you mean" has something to suggest
        Files.createDirectories(packsRoot.resolve("faulty/templates"));
        Files.writeString(packsRoot.resolve("faulty/templates/base_item.yml"), "base_item:\n  material: PAPER\n");
        var result = runner.request(new ReloadScope.Everything(), packsRoot);
        assertThat(result.completed()).isTrue();
        assertThat(quarantine.size()).isOne();
        Diagnostic reason = quarantine.all().get(0).reason();
        assertThat(reason.code()).isEqualTo(DiagnosticCodes.UNRESOLVED_REFERENCE);
        assertThat(reason.hint()).contains("base_item");
    }

    @Test
    void missingManifestExcludesPackResourcesButDoesNotAbort(@TempDir Path packsRoot) throws IOException {
        Path pack = packsRoot.resolve("manifestless");
        Files.createDirectories(pack.resolve("items"));
        Files.writeString(pack.resolve("items/thing.yml"), "thing:\n  material: PAPER\n");
        var result = runner.request(new ReloadScope.Everything(), packsRoot);
        assertThat(result.completed()).isTrue();
        assertThat(published(DiagnosticCodes.MANIFEST_INVALID)).isEqualTo(1);
        assertThat(runner.lastGraph().node(NamespacedId.parse("manifestless:thing"))).isEmpty();
    }

    private long published(dev.materione.api.diagnostics.DiagnosticCode code) {
        return diagnostics.recent(256).stream().filter(d -> d.code() == code).count();
    }

    private static void installFaultPack(
            Path packsRoot, String packId, String faultFile, String target) throws IOException {
        Path pack = packsRoot.resolve(packId);
        Files.createDirectories(pack.resolve(target).getParent());
        Files.writeString(
                pack.resolve(target),
                Files.readString(GoldenFiles.fixturesRoot().resolve("faults").resolve(faultFile)));
        Files.writeString(pack.resolve("pack.yml"), "id: " + packId + "\nversion: 1.0.0\nschema_version: 1\n");
    }
}
