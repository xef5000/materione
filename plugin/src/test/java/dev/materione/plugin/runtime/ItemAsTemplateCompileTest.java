package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.content.items.ItemType;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.pipeline.PipelineRunner;
import dev.materione.core.registry.RegistryService;
import dev.materione.pack.allocation.AllocationTable;
import dev.materione.pack.compile.PackCompiler;
import dev.materione.platform.text.MaterioneText;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Items as templates, compiled the way the plugin wires it (plugin.md section 3.1, #203): an item that
 * names another item as its {@code template:} ships exactly what the same item written out in full ships,
 * follows the parent when the parent changes (the next pipeline run, targeted or full), and a loop
 * through items costs only the items in the loop.
 */
class ItemAsTemplateCompileTest {

    @TempDir
    Path temp;

    private Path packs;
    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(512);
    private final QuarantineTracker quarantine = new QuarantineTracker();
    private final PipelineRunner runner = new PipelineRunner(diagnostics, quarantine);

    @BeforeEach
    void pack() throws IOException {
        packs = temp.resolve("packs");
        Path pack = packs.resolve("demo");
        Files.createDirectories(pack.resolve("items"));
        Files.createDirectories(pack.resolve("assets/textures/item"));
        Files.writeString(pack.resolve("pack.yml"), "id: demo\nversion: 1.0.0\nschema_version: 1\n");
        Files.copy(
                GoldenFiles.fixturesRoot().resolve("packs/sample/assets/textures/item/ruby.png"),
                pack.resolve("assets/textures/item/ruby.png"));
    }

    private void item(String name, String yaml) throws IOException {
        Files.writeString(packs.resolve("demo/items/" + name + ".yml"), yaml);
    }

    private PackCompiler.Outcome compile(ReloadScope scope) {
        assertThat(runner.request(scope, packs).completed()).isTrue();
        return new PackCompiler(MaterioneText::componentJson).compile(
                runner.lastPacks(),
                List.of(new ItemType()),
                AllocationTable.load(temp.resolve("storage/allocation.bin")),
                temp.resolve("generated"),
                diagnostics,
                quarantine,
                -1);
    }

    private static String entry(ZipFile zip, String name) throws IOException {
        var found = zip.getEntry(name);
        assertThat(found).as("zip entry " + name + " in " + names(zip)).isNotNull();
        return new String(zip.getInputStream(found).readAllBytes(), StandardCharsets.UTF_8);
    }

    private static List<String> names(ZipFile zip) {
        var out = new ArrayList<String>();
        zip.stream().forEach(e -> out.add(e.getName()));
        return out;
    }

    @Test
    void aLinkedItemShipsWhatTheSameItemWrittenOutShips() throws IOException {
        item("ruby", """
                ruby:
                  material: PAPER
                  texture: item/ruby
                  display_name: "Ruby"
                """);
        item("ruby_plus", "ruby_plus:\n  template: ruby\n");
        item("ruby_flat", """
                ruby_flat:
                  material: PAPER
                  texture: item/ruby
                  display_name: "Ruby"
                """);

        var outcome = compile(new ReloadScope.Everything());

        assertThat(quarantine.size()).isZero();
        try (var zip = new ZipFile(outcome.stagedZip().toFile())) {
            String linked = entry(zip, "assets/demo/items/ruby_plus.json");
            String flat = entry(zip, "assets/demo/items/ruby_flat.json");
            assertThat(linked.replace("ruby_plus", "X")).isEqualTo(flat.replace("ruby_flat", "X"));
            assertThat(entry(zip, "assets/demo/models/ruby_plus_model.json").replace("ruby_plus", "X"))
                    .isEqualTo(entry(zip, "assets/demo/models/ruby_flat_model.json").replace("ruby_flat", "X"));
        }
        assertThat(outcome.itemDefinitions()).isEqualTo(3);
    }

    @Test
    void theChildFollowsTheParentOnTheNextTargetedRun() throws IOException {
        item("ruby", "ruby:\n  material: PAPER\n  texture: item/ruby\n");
        item("ruby_plus", "ruby_plus:\n  template: ruby\n  display_name: \"Plus\"\n");
        runner.replaceFingerprints(dev.materione.core.pipeline.FingerprintCache.empty());
        compile(new ReloadScope.Everything());

        // the parent now points at a model of another texture; the child's file is not touched
        Path parent = packs.resolve("demo/items/ruby.yml");
        Files.writeString(parent, "ruby:\n  material: PAPER\n  texture: item/ruby\n  model_parent: item/handheld\n");
        var outcome = compile(new ReloadScope.FileSet(Set.of(parent)));

        try (var zip = new ZipFile(outcome.stagedZip().toFile())) {
            assertThat(entry(zip, "assets/demo/models/ruby_plus_model.json")).contains("item/handheld");
        }
    }

    @Test
    void aLoopThroughItemsQuarantinesTheLoopAndEveryOtherItemStillShips() throws IOException {
        item("ruby", "ruby:\n  material: PAPER\n  texture: item/ruby\n");
        item("a", "a:\n  template: b\n  material: PAPER\n");
        item("b", "b:\n  template: a\n");
        item("fine", "fine:\n  template: ruby\n");

        var outcome = compile(new ReloadScope.Everything());

        assertThat(quarantine.all()).extracting(q -> q.resource().toString())
                .containsExactlyInAnyOrder("demo:a", "demo:b");
        assertThat(quarantine.all()).allSatisfy(q ->
                assertThat(q.reason().code()).isEqualTo(DiagnosticCodes.TEMPLATE_CYCLE));
        try (var zip = new ZipFile(outcome.stagedZip().toFile())) {
            assertThat(entry(zip, "assets/demo/items/fine.json")).contains("demo:fine_model");
            assertThat(zip.getEntry("assets/demo/items/a.json")).isNull();
            assertThat(zip.getEntry("assets/demo/items/b.json")).isNull();
        }
        List<Diagnostic> crashes = diagnostics.recent(512).stream()
                .filter(d -> d.code().equals(DiagnosticCodes.PIPELINE_STAGE_FAILED)).toList();
        assertThat(crashes).isEmpty();
    }

    @Test
    void theRuntimeShadowServesTheLinkedItemWithTheParentsFieldsAndItsOwnOverrides() throws IOException {
        item("ruby", "ruby:\n  material: PAPER\n  display_name: \"Ruby\"\n  lore: [\"from parent\"]\n");
        item("ruby_plus", "ruby_plus:\n  template: ruby\n  display_name: \"Plus\"\n");
        assertThat(runner.request(new ReloadScope.Everything(), packs).completed()).isTrue();
        var registries = new RegistryService();
        var pipeline = new PipelineService(null, diagnostics, quarantine, registries, null,
                new PlatformDescriptor("1.21.4", false, false, false, Set.of()));
        pipeline.registerBuiltins();

        var shadow = pipeline.buildShadow(runner.lastPacks());

        var plus = shadow.item(NamespacedId.parse("demo:ruby_plus")).orElseThrow();
        assertThat(plus.material()).isEqualTo("PAPER");
        assertThat(plus.displayName()).isEqualTo("Plus");
        assertThat(plus.lore()).containsExactly("from parent");
        assertThat(shadow.item(NamespacedId.parse("demo:ruby")).orElseThrow().displayName()).isEqualTo("Ruby");
    }
}
