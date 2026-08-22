package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.registry.SelfDescription;
import dev.materione.content.items.ItemDefinition;
import dev.materione.content.items.ItemType;
import dev.materione.content.recipes.RecipeType;
import dev.materione.core.content.ContentType;
import dev.materione.core.content.PackEmitter;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.pipeline.PipelineResult;
import dev.materione.core.pipeline.PipelineRunner;
import dev.materione.core.pipeline.ReloadScope;
import dev.materione.core.runtime.ContentRuntime;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.delivery.SelfHostBackend;
import dev.materione.pack.allocation.AllocationTable;
import dev.materione.pack.compile.PackCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stage-pair fault injection (M1 exit criterion, plugin.md §4/D6): a fault
 * introduced at any stage boundary must leave the previous stage's output
 * intact and contain to one resource — never a half-compiled pack, never a
 * crashed reload. Each test injects a fault at one boundary of the real
 * stage composition (PipelineRunner → PackCompiler → ContentRuntime) and
 * asserts the pair invariant on both sides. The live-server equivalents of
 * these scenarios run in the harness (hot reload + mid-run fault drop).
 *
 * <p>Workspace layout per test: {@code <temp>/packs} is the packs root the
 * pipeline sees; {@code <temp>/storage} and {@code <temp>/generated} sit
 * beside it — inside the root ParseStage would treat them as packs.
 */
class StagePairFaultInjectionTest {

    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(256);
    private final QuarantineTracker quarantine = new QuarantineTracker();

    // --- PARSE ----------------------------------------------------------------

    @Test
    void parseFaultKeepsEveryValidNeighborLive(@TempDir Path dir) throws IOException {
        Path packsRoot = dir.resolve("packs");
        installFaultPack(packsRoot, "broken-syntax.yml", "items/broken-syntax.yml");
        copySamplePack(packsRoot);

        Run run = run(packsRoot, dir);
        assertThat(run.result().completed()).isTrue();
        assertThat(published(DiagnosticCodes.CONTENT_PARSE_FAILED)).isEqualTo(1);
        assertThat(quarantine.all()).hasSize(1);
        // the neighboring pack is fully live — the fault never crossed the boundary
        assertThat(run.runner().lastGraph().node(NamespacedId.parse("sample:ruby_sword"))).isPresent();
        assertThat(packsRoot.resolve("faulty/items/broken-syntax.yml")).exists();
    }

    // --- VALIDATE -------------------------------------------------------------

    @Test
    void validateFaultQuarantinesOnlyTheReference(@TempDir Path dir) throws IOException {
        Path packsRoot = dir.resolve("packs");
        installFaultPack(packsRoot, "unresolved-ref.yml", "items/unresolved-ref.yml");
        // a sibling template so the "did you mean" has something to suggest
        Files.createDirectories(packsRoot.resolve("faulty/templates"));
        Files.writeString(packsRoot.resolve("faulty/templates/base_item.yml"),
                "base_item:\n  material: PAPER\n");

        Run run = run(packsRoot, dir);
        assertThat(run.result().completed()).isTrue();
        assertThat(quarantine.all()).hasSize(1);
        Diagnostic reason = quarantine.all().get(0).reason();
        assertThat(reason.code()).isEqualTo(DiagnosticCodes.UNRESOLVED_REFERENCE);
        assertThat(reason.hint()).contains("base_item");
        // the referenced-to template itself stays a valid graph node
        assertThat(run.runner().lastGraph().node(NamespacedId.parse("faulty:base_item"))).isPresent();
    }

    // --- PARSE→COMPILE (schema + template cycle) -------------------------------

    @Test
    void schemaFaultSkipsTheResourceNotTheFile(@TempDir Path dir) throws IOException {
        // one file, two keyed resources: one invalid, one healthy (§7.2)
        Path packsRoot = dir.resolve("packs");
        installFaultPack(packsRoot, "missing-material.yml", "items/missing-material.yml");

        Run run = run(packsRoot, dir);
        assertThat(run.result().completed()).isTrue();
        assertThat(run.outcome()).isNotNull();
        assertThat(published(DiagnosticCodes.CONTENT_SCHEMA_INVALID)).isEqualTo(1);
        // the healthy sibling in the SAME file made it into the pack
        assertThat(zipEntries(run.outcome().stagedZip()))
                .contains("assets/faulty/items/healthy_sibling.json")
                .doesNotContain("assets/faulty/items/no_material.json");
    }

    @Test
    void templateCycleQuarantinesTheUserNotThePack(@TempDir Path dir) throws IOException {
        Path packsRoot = dir.resolve("packs");
        installFaultPack(packsRoot, "template-cycle.yml", "items/template-cycle.yml");
        Files.createDirectories(packsRoot.resolve("faulty/templates"));
        Files.writeString(packsRoot.resolve("faulty/templates/tpl_a.yml"),
                "tpl_a:\n  template: tpl_b\n  material: PAPER\n");
        Files.writeString(packsRoot.resolve("faulty/templates/tpl_b.yml"),
                "tpl_b:\n  template: tpl_a\n  material: PAPER\n");
        Files.writeString(packsRoot.resolve("faulty/items/bystander.yml"),
                "bystander:\n  material: PAPER\n  model: minecraft:item/diamond\n");

        Run run = run(packsRoot, dir);
        assertThat(run.result().completed()).isTrue();
        assertThat(published(DiagnosticCodes.CONTENT_SCHEMA_INVALID)).isEqualTo(1);
        assertThat(zipEntries(run.outcome().stagedZip()))
                .contains("assets/faulty/items/bystander.json")
                .doesNotContain("assets/faulty/items/cyclic_item.json");
    }

    // --- COMPILE (a crashing emitter) ------------------------------------------

    @Test
    void compileCrashSkipsTheResourceKeepsThePack(@TempDir Path dir) throws IOException {
        Path packsRoot = dir.resolve("packs");
        copySamplePack(packsRoot);
        Files.writeString(packsRoot.resolve("sample/items/victim.yml"),
                "victim:\n  material: PAPER\n  display_name: \"Doomed\"\n");

        // the item type with ONE poisoned resource: delegates everything,
        // throws only for the victim's compile (simulates a broken emitter)
        ContentType poisoned = new DelegatingItemType(new ItemType()) {
            @Override
            public void compile(Object definition, NamespacedId allocatedModelId, PackEmitter emitter) {
                if (definition instanceof ItemDefinition item
                        && item.id().value().equals("victim")) {
                    throw new IllegalStateException("emitter bug: boom");
                }
                super.compile(definition, allocatedModelId, emitter);
            }
        };

        Run run = run(packsRoot, dir, poisoned, new RecipeType());
        assertThat(run.result().completed()).isTrue();
        assertThat(run.outcome()).isNotNull();
        assertThat(published(DiagnosticCodes.PIPELINE_STAGE_FAILED)).isEqualTo(1);
        assertThat(quarantine.all())
                .anySatisfy(q -> assertThat(q.resource().value()).isEqualTo("victim"));
        // the pack was still built and every non-victim item is in it
        var entries = zipEntries(run.outcome().stagedZip());
        assertThat(entries).contains("assets/sample/items/ruby.json");
        assertThat(entries).doesNotContain("assets/sample/items/victim.json");
    }

    // --- ALLOCATE (a corrupt durable table) -------------------------------------

    @Test
    void corruptAllocationTableFailsSoft(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("storage"));
        Files.write(dir.resolve("storage/allocation.bin"),
                "not an allocation table, sorry".getBytes());
        copySamplePack(dir.resolve("packs"));

        AllocationTable table = AllocationTable.load(dir.resolve("storage/allocation.bin"));
        assertThat(table.isCorrupt()).isTrue();
        // fail-soft: allocation still answers (fresh assignments), the run proceeds
        assertThat(table.allocate(NamespacedId.parse("sample:ruby"), "custom-model-data"))
                .isEqualTo("1");

        Run run = run(dir.resolve("packs"), dir);
        assertThat(run.result().completed()).isTrue();
        assertThat(run.outcome()).isNotNull();
    }

    // --- APPLY (rollback keeps the previous shadow) ------------------------------

    @Test
    void applyPairRollbackRestoresThePreviousShadow() {
        var runtime = new ContentRuntime();
        RuntimeRegistries first = RuntimeRegistries.builder()
                .item(new RuntimeItem(NamespacedId.parse("sample:ruby"), "PAPER", null, "Ruby",
                        List.of(), "hash-1"))
                .build();
        RuntimeRegistries second = RuntimeRegistries.builder()
                .item(new RuntimeItem(NamespacedId.parse("sample:ruby"), "DIAMOND", null, "Ruby",
                        List.of(), "hash-2"))
                .build();

        runtime.apply(first, "sha-1");
        runtime.apply(second, "sha-2");
        assertThat(runtime.registries().item(NamespacedId.parse("sample:ruby")))
                .hasValueSatisfying(item -> assertThat(item.definitionHash()).isEqualTo("hash-2"));

        // the plugin's apply exception path: re-publish the previous shadow
        runtime.rollback(first, "sha-1");
        assertThat(runtime.registries().item(NamespacedId.parse("sample:ruby")))
                .hasValueSatisfying(item -> assertThat(item.definitionHash()).isEqualTo("hash-1"));
        assertThat(runtime.livePackSha256()).isEqualTo("sha-1");
    }

    // --- DELIVER (backend failure never touches apply) ---------------------------

    @Test
    void deliveryBackendFailureIsAnIOExceptionNotARuntimeCrash(@TempDir Path dir) throws IOException {
        var runtime = new ContentRuntime();
        runtime.apply(RuntimeRegistries.empty(), "sha-live");

        try (SelfHostBackend backend = new SelfHostBackend("127.0.0.1", 0, "127.0.0.1")) {
            // the SPI contract: publish failures are IOExceptions the caller
            // catches — apply is already done and stays live (§4.6)
            assertThatThrownBy(() -> backend.publish(dir.resolve("missing.zip"), "sha"))
                    .isInstanceOf(IOException.class);
            assertThat(backend.currentUrl()).doesNotContain("missing");
        }
        assertThat(runtime.livePackSha256()).isEqualTo("sha-live");
    }

    // --- composition --------------------------------------------------------

    private record Run(PipelineRunner runner, PipelineResult result,
            PackCompiler.Outcome outcome) {}

    /** The PipelineService composition minus its Bukkit-owned pieces. */
    private Run run(Path packsRoot, Path workDir, ContentType... itemAndRecipeTypes) {
        ContentType[] types = itemAndRecipeTypes.length > 0
                ? itemAndRecipeTypes
                : new ContentType[] {new ItemType(), new RecipeType()};
        var runner = new PipelineRunner(diagnostics, quarantine);
        PipelineResult result = runner.request(new ReloadScope.Everything(), packsRoot);
        var outcome = new PackCompiler().compile(
                runner.lastPacks(), List.of(types),
                AllocationTable.load(workDir.resolve("storage/allocation.bin")),
                workDir.resolve("generated"),
                diagnostics, quarantine);
        return new Run(runner, result, outcome);
    }

    private long published(dev.materione.api.diagnostics.DiagnosticCode code) {
        return diagnostics.recent(256).stream().filter(d -> d.code() == code).count();
    }

    private static List<String> zipEntries(Path zip) throws IOException {
        try (var file = new java.util.zip.ZipFile(zip.toFile())) {
            return file.stream().map(java.util.zip.ZipEntry::getName).collect(Collectors.toList());
        }
    }

    /** Copies the sample fixture pack verbatim (faults must never touch the repo). */
    private static void copySamplePack(Path packsRoot) throws IOException {
        Path sample = fixturesRoot().resolve("packs").resolve("sample");
        try (var walk = Files.walk(sample)) {
            for (Path file : walk.toList()) {
                Path target = packsRoot.resolve("sample").resolve(sample.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target);
                }
            }
        }
    }

    /** Creates pack "faulty" from one fixture fault file; returns the fault file path. */
    private static Path installFaultPack(Path packsRoot, String faultFile, String target)
            throws IOException {
        Path pack = packsRoot.resolve("faulty");
        Files.createDirectories(pack.resolve(target).getParent());
        Path faultTarget = pack.resolve(target);
        Files.writeString(faultTarget, Files.readString(fixturesRoot().resolve("faults").resolve(faultFile)));
        Files.writeString(pack.resolve("pack.yml"),
                "id: faulty\nversion: 1.0.0\nschema_version: 1\n");
        return faultTarget;
    }

    private static Path fixturesRoot() {
        return Path.of(System.getProperty("materione.fixtures.root"));
    }

    /** Test double: an item type that can poison one resource's compile. */
    private static class DelegatingItemType implements ContentType {
        private final ItemType delegate;

        DelegatingItemType(ItemType delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String directory() {
            return delegate.directory();
        }

        @Override
        public SelfDescription describe() {
            return delegate.describe();
        }

        @Override
        public Optional<Object> parse(
                NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
            return delegate.parse(id, definition, templates);
        }

        @Override
        public void compile(Object definition, NamespacedId allocatedModelId, PackEmitter emitter) {
            delegate.compile(definition, allocatedModelId, emitter);
        }
    }
}
