package dev.materione.pack.compile;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.content.ContentType;
import dev.materione.core.content.PackEmitter;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.pipeline.PipelineRunner;
import dev.materione.core.pipeline.ReloadScope;
import dev.materione.pack.allocation.AllocationTable;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiler behavior over the real fixture pack (D7 determinism, staging
 * layout, no-op detection). Uses a test-local item content type so the pack
 * module stays independent of {@code content/} (master §10); the assembly
 * wires the real one.
 */
class PackCompilerTest {

    /** Minimal item semantics for fixture compilation (mirrors ItemType's floor). */
    private static final class StubItemType implements ContentType {
        @Override
        public String name() {
            return "item";
        }

        @Override
        public String directory() {
            return "items";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Item", "stub", List.of(
                    new ParameterSchema("material", ParameterType.STRING, true, null, null, null, null)), "1.0.0");
        }

        @Override
        public Optional<Object> parse(
                NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
            Map<String, Object> merged = new LinkedHashMap<>();
            Object templateRef = definition.get("template");
            if (templateRef instanceof String ref) {
                Map<String, Object> base = templates.get(
                        ref.contains(":") ? ref : id.namespace() + ":" + ref);
                if (base != null) {
                    merged.putAll(base);
                }
            }
            merged.putAll(definition);
            merged.put("__id", id.toString());
            if (!(merged.get("material") instanceof String)) {
                return Optional.empty();
            }
            return Optional.of(merged);
        }

        @Override
        public void compile(Object definition, NamespacedId modelId, PackEmitter emitter) {
            @SuppressWarnings("unchecked")
            var merged = (Map<String, Object>) definition;
            NamespacedId id = NamespacedId.parse((String) merged.get("__id"));
            String texture = (String) merged.get("texture");
            String modelRef = (String) merged.get("model");
            if (texture != null) {
                modelRef = modelId.toString();
                var model = new LinkedHashMap<String, Object>();
                model.put("parent", merged.getOrDefault("model_parent", "item/generated"));
                model.put("textures", Map.of("layer0", texture.contains(":") ? texture
                        : id.namespace() + ":" + texture));
                emitter.model(modelId, model);
            }
            if (modelRef == null) {
                return;
            }
            emitter.itemDefinition(id, Map.of("model", Map.of(
                    "type", "reference",
                    "id", modelRef.contains(":") ? modelRef : id.namespace() + ":" + modelRef)));
        }
    }

    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(256);

    @Test
    void compilesFixturePackToStagedZip(@TempDir Path tempDir) throws IOException {
        var outcome = compileFixtures(tempDir);
        assertThat(outcome).isNotNull();
        assertThat(outcome.itemDefinitions()).isEqualTo(3); // ruby + ruby_sword + obsidian_axe
        assertThat(outcome.sha256()).isNotBlank();

        try (ZipFile zip = new ZipFile(outcome.stagedZip().toFile())) {
            assertThat(zip.getEntry("pack.mcmeta")).isNotNull();
            assertThat(zip.getEntry("assets/sample/items/ruby_sword.json")).isNotNull();
            assertThat(zip.getEntry("assets/sample/items/obsidian_axe.json")).isNotNull();
            // generated model (auto-model path: texture, no hand-authored model)
            assertThat(zip.getEntry("assets/sample/models/ruby_sword_model.json")).isNotNull();
            // obsidian_axe has a hand-authored model ref — no generated model, and the
            // hand-authored asset is copied verbatim
            assertThat(zip.getEntry("assets/sample/models/obsidian_axe_model.json")).isNotNull();
            assertThat(zip.getEntry("assets/sample/textures/obsidian/axe.png")).isNotNull();
            String mcmeta = new String(zip.getInputStream(zip.getEntry("pack.mcmeta")).readAllBytes());
            assertThat(mcmeta).contains("\"pack_format\":46");
        }
    }

    @Test
    void identicalInputsProduceByteIdenticalPacks(@TempDir Path tempDir) throws IOException {
        var first = compileFixtures(tempDir);
        var second = compileFixtures(tempDir);
        assertThat(second.sha256()).isEqualTo(first.sha256());
    }

    @Test
    void goldenLocksTheFixturePackBytes(@TempDir Path tempDir) throws IOException {
        var outcome = compileFixtures(tempDir);
        if (GoldenFiles.regenEnabled()) {
            GoldenFiles.writeGolden(outcome.stagedZip(), "m1-sample-pack.zip");
        }
        GoldenFiles.assertMatchesGolden(outcome.stagedZip(), "m1-sample-pack.zip");
    }

    @Test
    void allocationPersistsBeforeOutputs(@TempDir Path tempDir) throws IOException {
        var allocation = AllocationTable.load(tempDir.resolve("storage/allocation.bin"));
        compileFixtures(tempDir, allocation);
        // allocation flushed to disk even though nothing asked for a number
        assertThat(Files.exists(tempDir.resolve("storage/allocation.bin"))).isTrue();
        assertThat(allocation.liveCount()).isZero();
    }

    private PackCompiler.Outcome compileFixtures(Path tempDir) {
        return compileFixtures(tempDir, AllocationTable.load(tempDir.resolve("storage/allocation.bin")));
    }

    private PackCompiler.Outcome compileFixtures(Path tempDir, AllocationTable allocation) {
        var runner = new PipelineRunner(diagnostics, new QuarantineTracker());
        runner.request(new ReloadScope.Everything(), GoldenFiles.fixturesRoot().resolve("packs"));
        var compiler = new PackCompiler();
        var result = compiler.compile(
                runner.lastPacks(),
                List.of(new StubItemType()),
                allocation,
                tempDir.resolve("generated"),
                diagnostics);
        assertThat(diagnostics.recent(64).stream()
                .filter(d -> d.severity() == Severity.ERROR)
                .findAny())
                .as("no errors compiling the fixture pack")
                .isEmpty();
        return result;
    }
}
