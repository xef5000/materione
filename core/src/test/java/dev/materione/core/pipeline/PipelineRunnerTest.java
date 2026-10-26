package dev.materione.core.pipeline;

import dev.materione.api.pipeline.ReloadScope;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticTarget;
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
    void unresolvedReferenceTargetsItsFieldAndTheQuarantineNoticeCarriesIt(@TempDir Path packsRoot)
            throws IOException {
        installFaultPack(packsRoot, "faulty", "unresolved-ref.yml", "items/unresolved-ref.yml");
        Files.createDirectories(packsRoot.resolve("faulty/templates"));
        Files.writeString(packsRoot.resolve("faulty/templates/base_item.yml"), "base_item:\n  material: PAPER\n");
        var result = runner.request(new ReloadScope.Everything(), packsRoot);
        assertThat(result.completed()).isTrue();

        assertThat(quarantine.all().get(0).reason().target()).isEqualTo(DiagnosticTarget.field("template"));
        Diagnostic notice = quarantineNotice();
        assertThat(notice.message())
                .isEqualTo("resource faulty:broken_item quarantined: unresolved template 'base_ittm'");
        assertThat(notice.resource()).isEqualTo(NamespacedId.parse("faulty:broken_item"));
        assertThat(notice.target()).isEqualTo(DiagnosticTarget.field("template"));
    }

    @Test
    void quarantineNoticeNamesOnlyTheFirstFatalReason(@TempDir Path packsRoot) throws IOException {
        // two unresolved references in one resource: the reason the notice names
        // is the first in validator order (template before model), never both
        Path pack = packsRoot.resolve("twofaults");
        Files.createDirectories(pack.resolve("items"));
        Files.writeString(pack.resolve("pack.yml"), "id: twofaults\nversion: 1.0.0\nschema_version: 1\n");
        Files.writeString(pack.resolve("items/double.yml"), """
                double_fault:
                  template: ghost_base
                  model: ghost_model
                  material: PAPER
                """);
        var result = runner.request(new ReloadScope.Everything(), packsRoot);
        assertThat(result.completed()).isTrue();

        Diagnostic notice = quarantineNotice();
        assertThat(notice.message())
                .isEqualTo("resource twofaults:double_fault quarantined: unresolved template 'ghost_base'");
        assertThat(notice.target()).isEqualTo(DiagnosticTarget.field("template"));
        assertThat(published(DiagnosticCodes.CONTENT_QUARANTINED)).isOne();
    }

    @Test
    void danglingGlyphAliasQuarantines(@TempDir Path packsRoot) throws IOException {
        installFaultPack(packsRoot, "faulty", "dangling-glyph.yml", "glyphs/dangling-glyph.yml");
        var result = runner.request(new ReloadScope.Everything(), packsRoot);
        assertThat(result.completed()).isTrue();
        assertThat(quarantine.size()).isOne();
        Diagnostic reason = quarantine.all().get(0).reason();
        assertThat(reason.code()).isEqualTo(DiagnosticCodes.UNRESOLVED_REFERENCE);
        assertThat(reason.message()).contains("references unknown glyph");
    }

    @Test
    void chainedGlyphAliasQuarantines(@TempDir Path packsRoot) throws IOException {
        // a mutual alias→alias chain: both targets EXIST, so the existence
        // check passes — the alias-target check is what catches the chain
        // (aliases resolve one level at text time; without this both render
        // literal forever, silently)
        installFaultPack(packsRoot, "faulty", "chained-glyph-alias.yml", "glyphs/chained_alias.yml");
        Files.createDirectories(packsRoot.resolve("faulty/glyphs"));
        Files.writeString(packsRoot.resolve("faulty/glyphs/middle_alias.yml"),
                "middle_alias:\n  reference: chained_alias\n");
        var result = runner.request(new ReloadScope.Everything(), packsRoot);
        assertThat(result.completed()).isTrue();
        assertThat(quarantine.size()).isEqualTo(2);
        assertThat(quarantine.all()).allSatisfy(entry -> {
            assertThat(entry.reason().code()).isEqualTo(DiagnosticCodes.UNRESOLVED_REFERENCE);
            assertThat(entry.reason().message()).contains("one level only");
        });
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

    /** The one MTR-0010 notice the validate stage published for a quarantined resource. */
    private Diagnostic quarantineNotice() {
        return diagnostics.recent(256).stream()
                .filter(d -> d.code() == DiagnosticCodes.CONTENT_QUARANTINED)
                .findFirst()
                .orElseThrow();
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
