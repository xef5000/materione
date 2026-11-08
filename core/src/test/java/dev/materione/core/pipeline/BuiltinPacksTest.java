package dev.materione.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.document.DocumentStore;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The built-in packs (master architecture §7.3): content the plugin ships in its jar and loads through the same
 * pipeline as any pack. They are read from the jar's own file system and never extracted, they cannot be overridden by
 * a folder in {@code content/packs}, and a pack that cannot be read is reported and left out.
 */
class BuiltinPacksTest {

    @TempDir
    Path temp;

    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(256);
    private final QuarantineTracker quarantine = new QuarantineTracker();
    private final PipelineRunner runner = new PipelineRunner(diagnostics, quarantine);
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : open) {
            closeable.close();
        }
    }

    /** A jar with the given entries, and a class loader that sees only it. */
    private ClassLoader jar(Map<String, String> entries) throws IOException {
        Path file = temp.resolve("plugin-" + open.size() + ".jar");
        try (var out = new JarOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        URL[] urls = {file.toUri().toURL()};
        var loader = new URLClassLoader(urls, null);
        open.add(loader);
        return loader;
    }

    private static Map<String, String> shipped() {
        var entries = new LinkedHashMap<String, String>();
        entries.put("builtin-packs/index.txt", "# the packs this jar ships\ndemo\n");
        entries.put("builtin-packs/demo/pack.yml", "id: demo\nversion: 1.0.0\nschema_version: 1\ndepends: []\n");
        entries.put("builtin-packs/demo/items/gems/ruby.yml", "ruby:\n  material: PAPER\n  display_name: Ruby\n");
        entries.put("builtin-packs/demo/guis/panel.yml", "panel:\n  title: Panel\n  rows: 3\n");
        return entries;
    }

    private BuiltinPacks load(ClassLoader loader, List<Diagnostic> problems) {
        BuiltinPacks packs = BuiltinPacks.fromClasspath(loader, problems::add);
        open.add(packs);
        return packs;
    }

    private Path packs() throws IOException {
        return Files.createDirectories(temp.resolve("content/packs"));
    }

    @Test
    void aPackIsReadFromTheJarItselfAndNothingIsExtracted() throws Exception {
        var problems = new ArrayList<Diagnostic>();
        BuiltinPacks packs = load(jar(shipped()), problems);
        runner.builtinPacks(packs);

        assertThat(problems).isEmpty();
        assertThat(packs.ids()).containsExactly("demo");
        assertThat(packs.packs().get(0).directory().getFileSystem().provider().getScheme()).isEqualTo("jar");

        Path root = packs();
        assertThat(runner.request(new ReloadScope.Everything(), root).completed()).isTrue();

        ContentPack demo = runner.lastPacks().stream().filter(p -> p.id().equals("demo")).findFirst().orElseThrow();
        assertThat(demo.builtin()).isTrue();
        assertThat(demo.manifest()).isNotNull();
        assertThat(demo.resources()).extracting(r -> r.id().toString()).containsExactlyInAnyOrder("demo:ruby", "demo:panel");
        assertThat(runner.lastGraph().node(NamespacedId.parse("demo:ruby"), "item")).isPresent();
        assertThat(runner.lastGraph().node(NamespacedId.parse("demo:panel"), "gui")).isPresent();
        // the files stay in the jar: the packs root on disk holds nothing
        try (Stream<Path> listing = Files.list(root)) {
            assertThat(listing).isEmpty();
        }
        assertThat(Files.exists(temp.resolve("content/packs/demo"))).isFalse();
    }

    @Test
    void theShippedPackLoadsBesideThePacksOnDiskAndFirst() throws Exception {
        runner.builtinPacks(load(jar(shipped()), new ArrayList<>()));
        Path root = packs();
        Files.createDirectories(root.resolve("mine/items"));
        Files.writeString(root.resolve("mine/pack.yml"), "id: mine\nversion: 1.0.0\nschema_version: 1\ndepends: []\n");
        Files.writeString(root.resolve("mine/items/stone.yml"), "stone:\n  material: STONE\n");

        assertThat(runner.request(new ReloadScope.Everything(), root).completed()).isTrue();

        assertThat(runner.lastPacks()).extracting(ContentPack::id).containsExactly("demo", "mine");
        assertThat(runner.lastPacks()).extracting(ContentPack::builtin).containsExactly(true, false);
    }

    @Test
    void aFolderThatTakesAShippedPacksIdIsIgnoredAndReportedAndTheShippedPackStaysAsShipped() throws Exception {
        runner.builtinPacks(load(jar(shipped()), new ArrayList<>()));
        Path root = packs();
        // a user pack that tries to override the shipped ruby and to add an item to the shipped pack
        Files.createDirectories(root.resolve("demo/items"));
        Files.writeString(root.resolve("demo/pack.yml"), "id: demo\nversion: 9.9.9\nschema_version: 1\ndepends: []\n");
        Files.writeString(root.resolve("demo/items/ruby.yml"), "ruby:\n  material: DIAMOND\n");
        Files.writeString(root.resolve("demo/items/extra.yml"), "extra:\n  material: STICK\n");

        assertThat(runner.request(new ReloadScope.Everything(), root).completed()).isTrue();

        assertThat(runner.lastPacks()).extracting(ContentPack::id).containsExactly("demo");
        ContentPack demo = runner.lastPacks().get(0);
        assertThat(demo.builtin()).isTrue();
        assertThat(demo.manifest().version()).isEqualTo("1.0.0");
        assertThat(runner.lastGraph().node(NamespacedId.parse("demo:extra"), "item")).isEmpty();
        assertThat(runner.lastGraph().node(NamespacedId.parse("demo:ruby"), "item")).isPresent();
        assertThat(diagnostics.recent(256))
                .filteredOn(d -> d.code() == DiagnosticCodes.BUILTIN_PACK_ID_RESERVED)
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.severity()).isEqualTo(dev.materione.api.diagnostics.Severity.ERROR);
                    assertThat(d.message()).contains("demo");
                    assertThat(d.location().file()).endsWith("demo");
                });
    }

    @Test
    void targetedReloadsKeepTheShippedPackAndStillReportTheCollision() throws Exception {
        runner.builtinPacks(load(jar(shipped()), new ArrayList<>()));
        Path root = packs();
        Files.createDirectories(root.resolve("mine/items"));
        Files.writeString(root.resolve("mine/pack.yml"), "id: mine\nversion: 1.0.0\nschema_version: 1\ndepends: []\n");
        Files.writeString(root.resolve("mine/items/stone.yml"), "stone:\n  material: STONE\n");
        assertThat(runner.request(new ReloadScope.Everything(), root).completed()).isTrue();

        Files.writeString(root.resolve("mine/items/stone.yml"), "stone:\n  material: STONE\n  display_name: Stone\n");
        assertThat(runner.request(new ReloadScope.FileSet(Set.of(root.resolve("mine/items/stone.yml"))), root)
                .completed()).isTrue();
        assertThat(runner.lastPacks()).extracting(ContentPack::id).containsExactly("demo", "mine");

        // a scope that names the shipped pack cannot touch it or lose it
        assertThat(runner.request(new ReloadScope.OnePack("demo"), root).completed()).isTrue();
        assertThat(runner.lastPacks()).extracting(ContentPack::id).containsExactly("demo", "mine");
        assertThat(runner.lastGraph().node(NamespacedId.parse("demo:ruby"), "item")).isPresent();

        // a folder that appears later and takes the id is reported on the targeted run too
        Files.createDirectories(root.resolve("demo/items"));
        Files.writeString(root.resolve("demo/pack.yml"), "id: demo\nversion: 2\nschema_version: 1\n");
        diagnostics.recent(256); // drain nothing; the next run is judged by what it adds
        long before = diagnostics.recent(1000).stream()
                .filter(d -> d.code() == DiagnosticCodes.BUILTIN_PACK_ID_RESERVED).count();
        assertThat(runner.request(new ReloadScope.OnePack("mine"), root).completed()).isTrue();
        assertThat(diagnostics.recent(1000).stream()
                .filter(d -> d.code() == DiagnosticCodes.BUILTIN_PACK_ID_RESERVED).count()).isGreaterThan(before);
        assertThat(runner.lastPacks()).extracting(ContentPack::id).containsExactly("demo", "mine");
        assertThat(runner.lastPacks().get(0).builtin()).isTrue();
    }

    @Test
    void aPackThatCannotBeReadIsReportedAndLeftOutAndTheRestLoad() throws Exception {
        var entries = shipped();
        entries.put("builtin-packs/index.txt", "demo\nghost\n../escape\n");
        var problems = new ArrayList<Diagnostic>();

        BuiltinPacks packs = load(jar(entries), problems);

        assertThat(packs.ids()).containsExactly("demo");
        assertThat(problems).hasSize(2).allSatisfy(problem -> {
            assertThat(problem.code()).isEqualTo(DiagnosticCodes.BUILTIN_PACK_UNAVAILABLE);
            assertThat(problem.severity()).isEqualTo(dev.materione.api.diagnostics.Severity.ERROR);
        });
        assertThat(problems.get(0).message()).contains("ghost");
    }

    @Test
    void aJarWithNoIndexShipsNothing() throws Exception {
        var problems = new ArrayList<Diagnostic>();

        BuiltinPacks packs = load(jar(Map.of("other.txt", "x")), problems);

        assertThat(packs.packs()).isEmpty();
        assertThat(problems).isEmpty();
        runner.builtinPacks(packs);
        assertThat(runner.request(new ReloadScope.Everything(), packs()).completed()).isTrue();
        assertThat(runner.lastPacks()).isEmpty();
    }

    @Test
    void aShippedDocumentThatDoesNotParseIsQuarantinedAndNeverBlocksTheRest() throws Exception {
        var entries = shipped();
        entries.put("builtin-packs/demo/items/broken.yml", "broken: [unclosed\n");
        runner.builtinPacks(load(jar(entries), new ArrayList<>()));

        assertThat(runner.request(new ReloadScope.Everything(), packs()).completed()).isTrue();

        assertThat(runner.lastGraph().node(NamespacedId.parse("demo:ruby"), "item")).isPresent();
        assertThat(diagnostics.recent(256)).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo(DiagnosticCodes.CONTENT_PARSE_FAILED);
            assertThat(d.location().file()).contains("broken.yml");
        });
        assertThat(quarantine.size()).isPositive();
    }

    @Test
    void theStoreNeitherCreatesNorListsAPackWithAShippedPacksId() throws Exception {
        Path root = packs();
        var store = new DocumentStore(root, temp.resolve("revisions"));
        store.reservePackIds(Set.of("demo"));
        // a folder from before the plugin shipped the pack: it has a manifest, so it would be listed
        Files.createDirectories(root.resolve("demo"));
        Files.writeString(root.resolve("demo/pack.yml"), "id: demo\nversion: 1\nschema_version: 1\n");
        store.createPack("mine", "tester");

        assertThat(store.listPacks()).extracting(DocumentStore.PackSummary::id).containsExactly("mine");
        assertThatThrownBy(() -> store.createPack("demo", "tester"))
                .isInstanceOf(DocumentStore.ReservedPackException.class)
                .hasMessageContaining("demo");
        assertThatThrownBy(() -> store.createResource("demo", "items/x.yml", "x:\n  material: STICK\n", true, "tester"))
                .isInstanceOf(DocumentStore.ReservedPackException.class);
    }
}
