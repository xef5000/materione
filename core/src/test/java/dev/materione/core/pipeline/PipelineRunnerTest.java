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
import java.util.Set;
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
        // sample + corpus (the 50-item exit-criteria pack) + world (M2) + nested (#191) + guitext (#198) + probe (#212) + guilogic (#214)
        assertThat(result.packsScanned()).isEqualTo(7);
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
    void targetedReloadRetainsSyntaxQuarantineInUntouchedPacks(@TempDir Path packsRoot) throws IOException {
        installFaultPack(packsRoot, "broken", "broken-syntax.yml", "items/broken.yml");
        Files.createDirectories(packsRoot.resolve("good/items"));
        Files.writeString(packsRoot.resolve("good/pack.yml"), "id: good\n");
        Files.writeString(packsRoot.resolve("good/items/ok.yml"), "ok:\n  material: PAPER\n");
        var fingerprints = FingerprintCache.empty();
        runner.replaceFingerprints(fingerprints);
        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
        var original = quarantine.all();
        assertThat(original).hasSize(1);
        for (String id : java.util.List.of("broken", "good")) {
            fingerprints.record(id + "/pack.yml", FingerprintCache.sha256(packsRoot.resolve(id + "/pack.yml")));
        }

        assertThat(runner.request(new ReloadScope.OnePack("good"), packsRoot).completed()).isTrue();
        assertThat(diagnostics.recent(256)).noneMatch(d -> d.message().contains("targeted scope expanded"));
        assertThat(quarantine.all()).isEqualTo(original);

        Path broken = packsRoot.resolve("broken/items/broken.yml");
        Files.delete(broken);
        assertThat(runner.request(new ReloadScope.OnePack("good"), packsRoot).completed()).isTrue();
        assertThat(quarantine.all()).isEmpty();
        Files.writeString(broken, "broken: [");
        assertThat(runner.request(new ReloadScope.OnePack("broken"), packsRoot).completed()).isTrue();
        assertThat(quarantine.size()).isOne();
        Files.writeString(broken, "repaired:\n  material: PAPER\n");
        assertThat(runner.request(new ReloadScope.OnePack("broken"), packsRoot).completed()).isTrue();
        assertThat(quarantine.all()).isEmpty();
        assertThat(runner.lastGraph().node(NamespacedId.parse("broken:repaired"))).isPresent();
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
    void quarantineReflectsTheCurrentRunAfterRepeatedErrorsRepairsAndDeletes(@TempDir Path packsRoot)
            throws IOException {
        installFaultPack(packsRoot, "faulty", "dangling-glyph.yml", "glyphs/dangling-glyph.yml");
        Path glyph = packsRoot.resolve("faulty/glyphs/dangling-glyph.yml");
        for (int i = 0; i < 3; i++) {
            assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
            assertThat(quarantine.size()).isOne();
        }
        Files.createDirectories(packsRoot.resolve("faulty/assets/textures"));
        Files.write(packsRoot.resolve("faulty/assets/textures/heart.png"), new byte[] {1, 2, 3});
        Files.writeString(glyph, "fixed:\n  texture: heart\n");
        assertThat(runner.request(new ReloadScope.FileSet(java.util.Set.of(glyph)), packsRoot).completed())
                .isTrue();
        assertThat(quarantine.all()).isEmpty();
        Files.writeString(glyph, "broken_again:\n  reference: missing\n");
        assertThat(runner.request(new ReloadScope.OnePack("faulty"), packsRoot).completed()).isTrue();
        assertThat(quarantine.size()).isOne();
        Files.delete(glyph);
        assertThat(runner.request(new ReloadScope.OnePack("faulty"), packsRoot).completed()).isTrue();
        assertThat(quarantine.all()).isEmpty();
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

    /**
     * #194: two files in one pack declare one id. The first in path order owns it, the other is
     * reported with MTR-0056 naming both files, and the result is the same whichever file was
     * written first (the file system never decides the winner).
     */
    @Test
    void duplicateIdAcrossFilesIsDiagnosedWithTheSameWinnerForEveryWriteOrder(@TempDir Path packsRoot)
            throws IOException {
        Path pack = packsRoot.resolve("dup");
        Files.createDirectories(pack.resolve("items"));
        Files.writeString(pack.resolve("pack.yml"), "id: dup\nversion: 1.0.0\nschema_version: 1\n");
        // written later-path first, so creation order differs from path order
        Files.writeString(pack.resolve("items/b.yml"), "shared:\n  material: DIAMOND_SWORD\n");
        Files.writeString(pack.resolve("items/a.yml"), "shared:\n  material: IRON_SWORD\n");

        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();

        assertThat(quarantine.size()).isZero();
        assertThat(runner.lastGraph().node(NamespacedId.parse("dup:shared"), "item"))
                .hasValueSatisfying(node -> assertThat(node.sourceFile())
                        .endsWith(Path.of("items", "a.yml")));
        Diagnostic finding = diagnostics.recent(256).stream()
                .filter(d -> d.code() == DiagnosticCodes.DUPLICATE_RESOURCE_ID)
                .findFirst()
                .orElseThrow();
        assertThat(finding.severity()).isEqualTo(dev.materione.api.diagnostics.Severity.WARN);
        assertThat(finding.message()).isEqualTo("resource dup:shared (item) is declared in both"
                + " items/a.yml and items/b.yml; items/a.yml is used and items/b.yml's declaration is ignored");

        // a second pack, the same two files written in the opposite order, reaches the same winner
        Path reversed = packsRoot.resolve("dup-reversed");
        Files.createDirectories(reversed.resolve("items"));
        Files.writeString(reversed.resolve("pack.yml"), "id: dup-reversed\nversion: 1.0.0\nschema_version: 1\n");
        Files.writeString(reversed.resolve("items/a.yml"), "shared:\n  material: IRON_SWORD\n");
        Files.writeString(reversed.resolve("items/b.yml"), "shared:\n  material: DIAMOND_SWORD\n");
        var second = new PipelineRunner(new DiagnosticsBusService(256), new QuarantineTracker());
        assertThat(second.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
        assertThat(second.lastGraph().node(NamespacedId.parse("dup-reversed:shared"), "item"))
                .hasValueSatisfying(node -> assertThat(node.sourceFile())
                        .endsWith(Path.of("items", "a.yml")));
    }

    /**
     * Targeted reloads with fingerprints populated (the real boot path, where nothing outside the
     * scope drifted): an unchanged sibling in a touched pack must survive, and the duplicate winner
     * must move correctly when the file that owned the id changes.
     */
    @Test
    void targetedReloadKeepsUnchangedSiblingsOfATouchedPack(@TempDir Path packsRoot) throws IOException {
        Path pack = writeFingerprintedPack(packsRoot, "sib");
        Files.writeString(pack.resolve("items/a.yml"), "x:\n  material: STICK\n");
        Files.writeString(pack.resolve("items/b.yml"), "y:\n  material: STICK\n");
        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();

        Files.writeString(pack.resolve("items/a.yml"), "x2:\n  material: STICK\n");
        assertThat(runner.request(new ReloadScope.FileSet(Set.of(pack.resolve("items/a.yml"))), packsRoot)
                .completed()).isTrue();

        assertThat(runner.lastGraph().node(NamespacedId.parse("sib:x2"))).isPresent();
        assertThat(runner.lastGraph().node(NamespacedId.parse("sib:y"))).isPresent();
    }

    /** Deleting or renaming the winner promotes the loser through a targeted reload. */
    @Test
    void targetedReloadPromotesTheLoserWhenTheWinnerLetsTheIdGo(@TempDir Path packsRoot) throws IOException {
        Path pack = writeFingerprintedPack(packsRoot, "promo");
        Files.writeString(pack.resolve("items/a.yml"), "foo:\n  material: IRON_SWORD\n");
        Files.writeString(pack.resolve("items/b.yml"), "foo:\n  material: DIAMOND_SWORD\n");
        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
        assertThat(winnerSourceName(NamespacedId.parse("promo:foo"))).isEqualTo("a.yml");

        Files.writeString(pack.resolve("items/a.yml"), "renamed:\n  material: IRON_SWORD\n");
        assertThat(runner.request(new ReloadScope.FileSet(Set.of(pack.resolve("items/a.yml"))), packsRoot)
                .completed()).isTrue();

        assertThat(winnerSourceName(NamespacedId.parse("promo:foo"))).isEqualTo("b.yml");
        assertThat(runner.lastGraph().node(NamespacedId.parse("promo:renamed"))).isPresent();
        assertThat(quarantine.size()).isZero();
    }

    /** A new lexicographically-earlier declaration wins by targeted reload; the old copy is gone. */
    @Test
    void targetedReloadLetsANewEarlierDuplicateWinAndReportsIt(@TempDir Path packsRoot) throws IOException {
        Path pack = writeFingerprintedPack(packsRoot, "newdup");
        Files.writeString(pack.resolve("items/b.yml"), "foo:\n  material: DIAMOND_SWORD\n");
        Files.writeString(pack.resolve("items/c.yml"), "other:\n  material: STICK\n");
        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
        assertThat(winnerSourceName(NamespacedId.parse("newdup:foo"))).isEqualTo("b.yml");

        Files.writeString(pack.resolve("items/a.yml"), "foo:\n  material: IRON_SWORD\n");
        assertThat(runner.request(new ReloadScope.FileSet(Set.of(pack.resolve("items/a.yml"))), packsRoot)
                .completed()).isTrue();

        assertThat(winnerSourceName(NamespacedId.parse("newdup:foo"))).isEqualTo("a.yml");
        assertThat(runner.lastGraph().node(NamespacedId.parse("newdup:other"))).isPresent();
        assertThat(runner.lastPacks().stream().flatMap(p -> p.resources().stream())
                .filter(r -> r.id().equals(NamespacedId.parse("newdup:foo"))).count()).isOne();
        assertThat(diagnostics.recent(256).stream()
                .filter(d -> d.code() == DiagnosticCodes.DUPLICATE_RESOURCE_ID)
                .map(d -> d.message()))
                .anySatisfy(message -> assertThat(message).contains("items/a.yml and items/b.yml"));
    }

    /** A pack with fingerprints recorded by a full run, as the boot path leaves it. */
    private Path writeFingerprintedPack(Path packsRoot, String id) throws IOException {
        fingerprints = FingerprintCache.empty();
        runner.replaceFingerprints(fingerprints);
        Path pack = packsRoot.resolve(id);
        Files.createDirectories(pack.resolve("items"));
        Files.writeString(pack.resolve("pack.yml"), "id: " + id + "\nversion: 1.0.0\nschema_version: 1\n");
        // pack.yml is not a content document, so the parse stage never records it; the drift check
        // would treat it as drifted and always expand. Record it as the boot path does.
        fingerprints.record(id + "/pack.yml", FingerprintCache.sha256(pack.resolve("pack.yml")));
        return pack;
    }

    private FingerprintCache fingerprints;

    private String winnerSourceName(NamespacedId id) {
        return runner.lastGraph().node(id, "item")
                .map(node -> node.sourceFile().getFileName().toString())
                .orElse(null);
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
