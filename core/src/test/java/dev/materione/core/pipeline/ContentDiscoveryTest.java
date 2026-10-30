package dev.materione.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.document.DocumentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Content discovery is one rule everywhere (plugin architecture §4.2): every
 * {@code .yml} under a content directory, at any depth, dot-directories and
 * linked directories excluded, sorted by pack-relative path. Resource identity
 * stays the file's top-level keys, so a nested file behaves like a sibling.
 */
class ContentDiscoveryTest {

    @TempDir
    Path temp;

    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(256);
    private final QuarantineTracker quarantine = new QuarantineTracker();
    private final PipelineRunner runner = new PipelineRunner(diagnostics, quarantine);

    @Test
    void nestedDocumentsAreDiscoveredInSortedPathOrder() throws IOException {
        Path alpha = pack("alpha");
        write(alpha.resolve("items/z/b.yml"), "b:\n  material: STICK\n");
        write(alpha.resolve("items/a.yml"), "a:\n  material: STICK\n");
        write(alpha.resolve("items/m/n/deep.yml"), "deep:\n  material: STICK\n");
        write(alpha.resolve("items/weapons/sword.yml"), "sword:\n  material: IRON_SWORD\n");

        assertThat(discovered(alpha, "items")).containsExactly(
                "items/a.yml", "items/m/n/deep.yml", "items/weapons/sword.yml", "items/z/b.yml");
    }

    @Test
    void dotDirectoriesAreNeverContent() throws IOException {
        Path alpha = pack("alpha");
        write(alpha.resolve("items/visible.yml"), "visible:\n  material: STICK\n");
        write(alpha.resolve("items/.trash/old.yml"), "old:\n  material: STICK\n");
        write(alpha.resolve("items/weapons/.stash/held.yml"), "held:\n  material: STICK\n");
        write(alpha.resolve(".materione/notes.yml"), "notes:\n  material: STICK\n");
        // a dot-directory at the packs root is not a pack, not even a broken one
        write(packs().resolve(".cache/items/ghost.yml"), "ghost:\n  material: STICK\n");

        assertThat(discovered(alpha, "items")).containsExactly("items/visible.yml");
        assertThat(runner.request(new ReloadScope.Everything(), packs()).completed()).isTrue();
        assertThat(runner.lastPacks()).extracting(pack -> pack.id()).containsExactly("alpha");
        assertThat(diagnostics.recent(256)).noneMatch(d -> d.message().contains(".cache"));
        assertThat(runner.lastGraph().node(NamespacedId.parse("alpha:old"))).isEmpty();
    }

    @Test
    void linkedDirectoriesAreNotEntered() throws IOException {
        Path alpha = pack("alpha");
        write(temp.resolve("elsewhere/linked.yml"), "linked:\n  material: STICK\n");
        write(alpha.resolve("items/own.yml"), "own:\n  material: STICK\n");
        try {
            Files.createSymbolicLink(alpha.resolve("items/shared"), temp.resolve("elsewhere"));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "symbolic links unavailable on this host: " + e);
        }

        assertThat(discovered(alpha, "items")).containsExactly("items/own.yml");
    }

    @Test
    void nestedResourcesReachTheGraphWithTheirOwnSourceFile() throws IOException {
        Path alpha = pack("alpha");
        write(alpha.resolve("items/weapons/sword.yml"), "sword:\n  material: IRON_SWORD\n");

        assertThat(runner.request(new ReloadScope.Everything(), packs()).completed()).isTrue();
        assertThat(quarantine.size()).isZero();
        assertThat(runner.lastGraph().node(NamespacedId.parse("alpha:sword"), "item"))
                .hasValueSatisfying(node -> assertThat(node.sourceFile())
                        .endsWith(Path.of("items", "weapons", "sword.yml")));
    }

    @Test
    void duplicateIdsKeepTheFirstPathAndReportTheRestWhetherNestedOrSibling() throws IOException {
        // siblings: the first file in path order keeps the id, the other is excluded (#194, MTR-0056)
        Path siblings = pack("siblings");
        write(siblings.resolve("items/a.yml"), "dup:\n  material: IRON_SWORD\n");
        write(siblings.resolve("items/b.yml"), "dup:\n  material: DIAMOND_SWORD\n");
        // nested: the same rule applies across folders, no special case for depth
        Path nested = pack("nested");
        write(nested.resolve("items/a.yml"), "dup:\n  material: IRON_SWORD\n");
        write(nested.resolve("items/z/b.yml"), "dup:\n  material: DIAMOND_SWORD\n");

        assertThat(runner.request(new ReloadScope.Everything(), packs()).completed()).isTrue();

        // the loser is not a resource at all: validation, graph and compile see one declaration
        assertThat(resourcesWithId(NamespacedId.parse("siblings:dup"))).isEqualTo(1);
        assertThat(winnerPath(NamespacedId.parse("siblings:dup"))).isEqualTo("items/a.yml");
        assertThat(resourcesWithId(NamespacedId.parse("nested:dup"))).isEqualTo(1);
        assertThat(winnerPath(NamespacedId.parse("nested:dup"))).isEqualTo("items/a.yml");
        assertThat(quarantine.size()).isZero();
        assertThat(diagnostics.recent(256).stream()
                .filter(d -> d.code() == DiagnosticCodes.DUPLICATE_RESOURCE_ID)
                .map(d -> d.message()))
                .containsExactlyInAnyOrder(
                        "resource siblings:dup (item) is declared in both items/a.yml and items/b.yml;"
                                + " items/a.yml is used and items/b.yml's declaration is ignored",
                        "resource nested:dup (item) is declared in both items/a.yml and items/z/b.yml;"
                                + " items/a.yml is used and items/z/b.yml's declaration is ignored");
    }

    @Test
    void editingANestedFileReloadsItThroughAFileSet() throws IOException {
        Path alpha = pack("alpha");
        write(alpha.resolve("items/a.yml"), "other:\n  material: STICK\n");
        Path sword = write(alpha.resolve("items/weapons/sword.yml"), "sword:\n  material: IRON_SWORD\n");
        assertThat(runner.request(new ReloadScope.Everything(), packs()).completed()).isTrue();

        write(sword, "sword:\n  material: DIAMOND_SWORD\n");
        assertThat(runner.request(new ReloadScope.FileSet(Set.of(sword)), packs()).completed()).isTrue();

        assertThat(materialOf(NamespacedId.parse("alpha:sword"))).isEqualTo("DIAMOND_SWORD");
        assertThat(materialOf(NamespacedId.parse("alpha:other"))).isEqualTo("STICK");
    }

    @Test
    void editingANestedFileOutsideTheScopeIsNeverMissed() throws IOException {
        Path alpha = pack("alpha");
        Path sword = write(alpha.resolve("items/weapons/sword.yml"), "sword:\n  material: IRON_SWORD\n");
        Path other = write(alpha.resolve("items/a.yml"), "other:\n  material: STICK\n");
        assertThat(runner.request(new ReloadScope.Everything(), packs()).completed()).isTrue();

        // the nested edit is out of scope for this request, so it must still land
        write(sword, "sword:\n  material: DIAMOND_SWORD\n");
        assertThat(runner.request(new ReloadScope.FileSet(Set.of(other)), packs()).completed()).isTrue();

        assertThat(materialOf(NamespacedId.parse("alpha:sword"))).isEqualTo("DIAMOND_SWORD");
    }

    @Test
    void sourceDocumentsAreTheManifestAndContentDocumentsOnly() throws IOException {
        Path alpha = pack("alpha");
        write(alpha.resolve("items/a.yml"), "a:\n  material: STICK\n");
        write(alpha.resolve("items/x/b.yml"), "b:\n  material: STICK\n");
        write(alpha.resolve("assets/textures/c.yml"), "c: 1\n");
        write(alpha.resolve(".materione/d.yml"), "d: 1\n");
        write(alpha.resolve("loose.yml"), "e: 1\n");

        List<String> sources = PackLayout.sourceDocuments(alpha, failure -> {
                    throw new AssertionError("unexpected unreadable folder", failure);
                }).stream()
                .map(file -> relative(alpha, file))
                .toList();
        assertThat(sources).containsExactly("pack.yml", "items/a.yml", "items/x/b.yml");
    }

    @Test
    void contentDocumentPathsFollowTheSameRule() {
        assertThat(PackLayout.isContentDocumentPath("items/a.yml")).isTrue();
        assertThat(PackLayout.isContentDocumentPath("items/weapons/sword.yml")).isTrue();
        assertThat(PackLayout.isContentDocumentPath("items/.trash/old.yml")).isFalse();
        assertThat(PackLayout.isContentDocumentPath("items/a.yml.tmp-1")).isFalse();
        assertThat(PackLayout.isContentDocumentPath("assets/a.yml")).isFalse();
        assertThat(PackLayout.isContentDocumentPath("pack.yml")).isFalse();
        assertThat(PackLayout.isContentDocumentPath("items/gem.png")).isFalse();
    }

    @Test
    void theBrowserAndThePipelineListTheSameDocuments() throws IOException {
        Path alpha = pack("alpha");
        write(alpha.resolve("items/a.yml"), "a:\n  material: STICK\n");
        write(alpha.resolve("items/weapons/sword.yml"), "sword:\n  material: IRON_SWORD\n");
        write(alpha.resolve("items/.trash/old.yml"), "old:\n  material: STICK\n");
        write(alpha.resolve("items/notes.yaml"), "notes:\n  material: STICK\n");
        write(alpha.resolve("items/readme.txt"), "not a document\n");
        write(alpha.resolve("templates/base.yml"), "base:\n  material: STICK\n");

        Set<String> pipeline = PackLayout.CONTENT_DIRS.stream()
                .flatMap(dir -> discovered(alpha, dir).stream())
                .collect(Collectors.toSet());
        var store = new DocumentStore(packs(), temp.resolve("revisions"));
        Set<String> browser = store.listResources("alpha", null, null).stream()
                .map(DocumentStore.ResourceRef::path)
                .collect(Collectors.toSet());
        Set<String> summaries = store.listResourceDocuments("alpha", null).stream()
                .map(document -> document.resource().path())
                .collect(Collectors.toSet());

        assertThat(pipeline).containsExactlyInAnyOrder(
                "items/a.yml", "items/weapons/sword.yml", "templates/base.yml");
        assertThat(browser).isEqualTo(pipeline);
        assertThat(summaries).isEqualTo(pipeline);
    }

    // --- helpers -------------------------------------------------------------

    private Path packs() {
        return temp.resolve("packs");
    }

    private Path pack(String id) throws IOException {
        Path dir = packs().resolve(id);
        write(dir.resolve("pack.yml"), "id: " + id + "\n");
        return dir;
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    /** Pack-relative paths of one content directory's discovered documents, in discovery order. */
    private static List<String> discovered(Path packDir, String contentDir) {
        return PackLayout.contentDocuments(packDir, contentDir, failure -> {
                    throw new AssertionError("unexpected unreadable folder", failure);
                }).stream()
                .map(file -> relative(packDir, file))
                .toList();
    }

    private static String relative(Path packDir, Path file) {
        return packDir.relativize(file).toString().replace('\\', '/');
    }

    private long resourcesWithId(NamespacedId id) {
        return runner.lastPacks().stream()
                .flatMap(pack -> pack.resources().stream())
                .filter(resource -> resource.id().equals(id))
                .count();
    }

    /** The pack-relative path of the file the graph node for {@code id} came from. */
    private String winnerPath(NamespacedId id) {
        return runner.lastGraph().node(id, "item")
                .map(node -> relative(packs().resolve(id.namespace()), node.sourceFile()))
                .orElse(null);
    }

    private String materialOf(NamespacedId id) {
        return runner.lastPacks().stream()
                .flatMap(pack -> pack.resources().stream())
                .filter(resource -> resource.id().equals(id))
                .map(resource -> String.valueOf(
                        ((Map<?, ?>) resource.document().data().get(id.value())).get("material")))
                .findFirst()
                .orElse(null);
    }
}
