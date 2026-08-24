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
import dev.materione.content.blocks.BlockType;
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
        public void compile(
                Object definition, NamespacedId modelId, PackEmitter emitter,
                dev.materione.core.content.Allocations allocations) {
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

    /** Minimal block semantics for blockstate emission (mirrors BlockType's floor). */
    private static final class StubBlockType implements ContentType {
        @Override
        public String name() {
            return "block";
        }

        @Override
        public String directory() {
            return "blocks";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Block", "stub", List.of(
                    new ParameterSchema("texture", ParameterType.STRING, true, null, null, null, null)), "1.0.0");
        }

        @Override
        public Optional<Object> parse(
                NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
            if (!(definition.get("texture") instanceof String)) {
                return Optional.empty();
            }
            var merged = new LinkedHashMap<String, Object>(definition);
            merged.put("__id", id.toString());
            return Optional.of(merged);
        }

        @Override
        public void compile(
                Object definition, NamespacedId modelId, PackEmitter emitter,
                dev.materione.core.content.Allocations allocations) {
            @SuppressWarnings("unchecked")
            var merged = (Map<String, Object>) definition;
            NamespacedId id = NamespacedId.parse((String) merged.get("__id"));
            String host = merged.get("host") instanceof String explicit ? explicit : "note_block";
            int slot = Integer.parseInt(allocations.allocate(id, "block:" + host));
            emitter.vanillaFile("blockstates/" + host + ".json", Map.of("variants", Map.of(
                    dev.materione.core.blocks.BlockStateSpaces.variantKey(
                            dev.materione.core.blocks.BlockStateSpaces.Technique.NOTE_BLOCK, slot),
                    Map.of("model", modelId.toString()))));
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

            // atlas coverage: obsidian/ is outside vanilla's item|block scan and
            // must get a directory source; item/ is vanilla-covered and must not
            String atlas = entryText(zip, "assets/minecraft/atlases/blocks.json");
            assertThat(atlas)
                    .contains("\"source\":\"obsidian\"")
                    .doesNotContain("\"source\":\"item\"")
                    .doesNotContain("\"source\":\"block\"");

            // mcmeta: floor base plus a range reaching past newer clients, in
            // BOTH schema generations — supported_formats for pre-25w31a
            // clients, min_format/max_format for 1.21.9+/26.x clients (which
            // ignore supported_formats entirely)
            String mcmeta = entryText(zip, "pack.mcmeta");
            assertThat(mcmeta)
                    .contains("\"pack_format\":46")
                    .contains("supported_formats")
                    .contains("min_format")
                    .contains("max_format")
                    .contains(String.valueOf(PackFormats.MAX_KNOWN_FORMAT));
        }
    }

    @Test
    void detectedServerFormatWidensTheAdvertisedRange(@TempDir Path tempDir) throws IOException {
        Path packsRoot = tempDir.resolve("packs/widen");
        writePackSkeleton(packsRoot, "widen");
        writeFile(packsRoot.resolve("items/t.yml"), "t:\n  material: STICK\n".getBytes());

        // a future server (format 96) reporting itself: the pack must be
        // acceptable to its own clients without a table bump
        var outcome = compilePack(
                tempDir, "widen",
                AllocationTable.load(tempDir.resolve("storage-widen/allocation.bin")),
                List.of(new StubItemType()),
                96);

        assertThat(outcome).isNotNull();
        try (ZipFile zip = new ZipFile(outcome.stagedZip().toFile())) {
            String mcmeta = entryText(zip, "pack.mcmeta");
            assertThat(mcmeta)
                    .contains("\"min_format\":46")
                    .contains("\"max_format\":96")
                    .contains("\"supported_formats\":[46,96]");
        }
    }

    @Test
    void uncoveredRootTextureWarnsAndShipsNoAtlas(@TempDir Path tempDir) throws IOException {
        Path packsRoot = tempDir.resolve("packs/atlaswarn");
        writePackSkeleton(packsRoot, "atlaswarn");
        writeFile(packsRoot.resolve("assets/textures/root.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        // texture at the textures/ root — no directory source can reach it
        writeFile(packsRoot.resolve("items/t.yml"), "t:\n  material: STICK\n  texture: root\n".getBytes());

        var outcome = compilePack(tempDir, "atlaswarn");

        assertThat(outcome).isNotNull();
        assertThat(diagnostics.recent(64).stream()
                .filter(d -> "MTR-0017".equals(d.code().code()))
                .findFirst())
                .as("root-level texture must raise MTR-0017")
                .isPresent();
        try (ZipFile zip = new ZipFile(outcome.stagedZip().toFile())) {
            assertThat(zip.getEntry("assets/minecraft/atlases/blocks.json")).isNull();
        }
    }

    @Test
    void userShippedAtlasIsMergedNotClobbered(@TempDir Path tempDir) throws IOException {
        Path packsRoot = tempDir.resolve("packs/minecraft");
        writePackSkeleton(packsRoot, "minecraft");
        // pack assets are namespace-rooted, so assets/atlases/blocks.json in a
        // pack named "minecraft" is the one way user content reaches the
        // vanilla-loaded atlas path today
        writeFile(packsRoot.resolve("assets/atlases/blocks.json"),
                "{\"sources\":[{\"type\":\"single\",\"resource\":\"minecraft:block/dirt\"}]}".getBytes());
        writeFile(packsRoot.resolve("assets/textures/custom/t.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        writeFile(packsRoot.resolve("items/t.yml"), "t:\n  material: STICK\n  texture: custom/t\n".getBytes());

        var outcome = compilePack(tempDir, "minecraft");

        assertThat(outcome).isNotNull();
        try (ZipFile zip = new ZipFile(outcome.stagedZip().toFile())) {
            String atlas = entryText(zip, "assets/minecraft/atlases/blocks.json");
            // the user's source survives alongside the generated directory source
            assertThat(atlas)
                    .contains("minecraft:block/dirt")
                    .contains("\"source\":\"custom\"");
        }
        assertThat(diagnostics.recent(64).stream()
                .filter(d -> d.severity() == Severity.ERROR)
                .findAny())
                .as("no errors compiling the merged-atlas pack")
                .isEmpty();
    }

    @Test
    void blocksClaimStateSlotsIntoMergedBlockstates(@TempDir Path tempDir) throws IOException {
        Path packsRoot = tempDir.resolve("packs/blocks");
        writePackSkeleton(packsRoot, "blocks");
        writeFile(packsRoot.resolve("assets/textures/block/lamp.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        writeFile(packsRoot.resolve("blocks/lamp.yml"), "lamp:\n  texture: block/lamp\n".getBytes());
        writeFile(packsRoot.resolve("blocks/tube.yml"), "tube:\n  texture: block/lamp\n".getBytes());

        var allocation = AllocationTable.load(tempDir.resolve("storage-blocks/allocation.bin"));
        var outcome = compilePack(tempDir, "blocks", allocation, List.of(new StubItemType(), new StubBlockType()));

        assertThat(outcome).isNotNull();
        assertThat(allocation.liveCount()).isEqualTo(2); // two durable state slots claimed
        try (ZipFile zip = new ZipFile(outcome.stagedZip().toFile())) {
            String noteBlock = entryText(zip, "assets/minecraft/blockstates/note_block.json");
            assertThat(noteBlock)
                    .contains("\"instrument=harp,note=1,powered=false\"")
                    .contains("\"instrument=harp,note=2,powered=false\"");
            // no host file for techniques nobody used
            assertThat(zip.getEntry("assets/minecraft/blockstates/tripwire.json")).isNull();
        }
    }

    @Test
    void crossPackBlocksMergeIntoOneBlockstate(@TempDir Path tempDir) throws IOException {
        writePackSkeleton(tempDir.resolve("packs/alpha"), "alpha");
        writeFile(tempDir.resolve("packs/alpha/assets/textures/block/a.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        writeFile(tempDir.resolve("packs/alpha/blocks/a.yml"), "a:\n  texture: block/a\n".getBytes());
        writePackSkeleton(tempDir.resolve("packs/beta"), "beta");
        writeFile(tempDir.resolve("packs/beta/assets/textures/block/b.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        writeFile(tempDir.resolve("packs/beta/blocks/b.yml"), "b:\n  texture: block/b\n".getBytes());

        var quarantine = new QuarantineTracker();
        var runner = new PipelineRunner(diagnostics, quarantine);
        runner.request(new ReloadScope.Everything(), tempDir.resolve("packs"));
        var outcome = new PackCompiler().compile(
                runner.lastPacks(),
                List.of(new StubItemType(), new StubBlockType()),
                AllocationTable.load(tempDir.resolve("storage-merge/allocation.bin")),
                tempDir.resolve("generated-merge"),
                diagnostics,
                quarantine,
                -1);

        assertThat(outcome).isNotNull();
        try (ZipFile zip = new ZipFile(outcome.stagedZip().toFile())) {
            // one file, two claims — never a clobber
            String noteBlock = entryText(zip, "assets/minecraft/blockstates/note_block.json");
            assertThat(noteBlock)
                    .contains("alpha:a_model")
                    .contains("beta:b_model");
        }
    }

    @Test
    void userShippedBlockstatesMergeNotClobbered(@TempDir Path tempDir) throws IOException {
        Path packsRoot = tempDir.resolve("packs/minecraft");
        writePackSkeleton(packsRoot, "minecraft");
        // pack assets are namespace-rooted (dest = assets/<packId>/<relative>), so a pack
        // literally named "minecraft" is how shipped content reaches vanilla's path today;
        // this file claims slot 1's exact variant key ahead of the generated allocation
        writeFile(packsRoot.resolve("assets/blockstates/note_block.json"),
                "{\"variants\":{\"instrument=harp,note=1,powered=false\":{\"model\":\"minecraft:block/user\"}}}"
                        .getBytes());
        writeFile(packsRoot.resolve("assets/textures/block/u.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        writeFile(packsRoot.resolve("blocks/u.yml"), "u:\n  texture: block/u\n".getBytes());

        var quarantine = new QuarantineTracker();
        var runner = new PipelineRunner(diagnostics, quarantine);
        runner.request(new ReloadScope.Everything(), tempDir.resolve("packs"));
        var outcome = new PackCompiler().compile(
                runner.lastPacks(),
                List.of(new StubItemType(), new StubBlockType()),
                AllocationTable.load(tempDir.resolve("storage-minecraft/allocation.bin")),
                tempDir.resolve("generated-minecraft"),
                diagnostics,
                quarantine,
                -1);

        assertThat(outcome).isNotNull();
        try (ZipFile zip = new ZipFile(outcome.stagedZip().toFile())) {
            String noteBlock = entryText(zip, "assets/minecraft/blockstates/note_block.json");
            // the user's explicit claim wins the contested key; the generated claim is dropped
            assertThat(noteBlock).contains("minecraft:block/user");
            assertThat(noteBlock).doesNotContain("minecraft:u_model");
        }
        assertThat(diagnostics.recent(64).stream()
                .filter(d -> "MTR-0020".equals(d.code().code()))
                .findFirst())
                .as("the contested variant key must raise MTR-0020")
                .isPresent();
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
        Path allocationFile = tempDir.resolve("storage/allocation.bin");
        var allocation = AllocationTable.load(allocationFile);
        compileFixtures(tempDir, allocation);
        // the lamp's state slot flushed to disk before the pack was written
        assertThat(Files.exists(allocationFile)).isTrue();
        assertThat(allocation.liveCount()).isEqualTo(1);
    }

    private PackCompiler.Outcome compileFixtures(Path tempDir) {
        return compileFixtures(tempDir, AllocationTable.load(tempDir.resolve("storage/allocation.bin")));
    }

    private PackCompiler.Outcome compileFixtures(Path tempDir, AllocationTable allocation) {
        var quarantine = new QuarantineTracker();
        var runner = new PipelineRunner(diagnostics, quarantine);
        runner.request(new ReloadScope.Everything(), GoldenFiles.fixturesRoot().resolve("packs"));
        // the golden locks the sample pack's bytes only; the corpus pack has
        // its own compile coverage (harness perf budget test)
        var samplePacks = runner.lastPacks().stream()
                .filter(p -> p.id().equals("sample")).toList();
        var compiler = new PackCompiler();
        var result = compiler.compile(
                samplePacks,
                // the REAL block and furniture content types: the golden locks
                // actual compile behavior (state allocation, shaped
                // blockstates, generated display models)
                List.of(new StubItemType(), new BlockType(),
                        new dev.materione.content.furniture.FurnitureType()),
                allocation,
                tempDir.resolve("generated"),
                diagnostics,
                quarantine,
                -1); // table-bounded spec: goldens must not depend on a server
        assertThat(diagnostics.recent(64).stream()
                .filter(d -> d.severity() == Severity.ERROR)
                .findAny())
                .as("no errors compiling the fixture pack")
                .isEmpty();
        return result;
    }

    /** Compiles one pack from a temp packs root (atlas/merge scenarios). */
    private PackCompiler.Outcome compilePack(Path tempDir, String packId) {
        return compilePack(
                tempDir, packId,
                AllocationTable.load(tempDir.resolve("storage-" + packId + "/allocation.bin")),
                List.of(new StubItemType()));
    }

    private PackCompiler.Outcome compilePack(
            Path tempDir, String packId, AllocationTable allocation, List<ContentType> contentTypes) {
        return compilePack(tempDir, packId, allocation, contentTypes, -1);
    }

    private PackCompiler.Outcome compilePack(
            Path tempDir, String packId, AllocationTable allocation, List<ContentType> contentTypes,
            int detectedMaxFormat) {
        var quarantine = new QuarantineTracker();
        var runner = new PipelineRunner(diagnostics, quarantine);
        runner.request(new ReloadScope.Everything(), tempDir.resolve("packs"));
        var packs = runner.lastPacks().stream()
                .filter(p -> p.id().equals(packId)).toList();
        return new PackCompiler().compile(
                packs,
                contentTypes,
                allocation,
                tempDir.resolve("generated-" + packId),
                diagnostics,
                quarantine,
                detectedMaxFormat);
    }

    private static void writePackSkeleton(Path packDir, String id) throws IOException {
        writeFile(packDir.resolve("pack.yml"), ("id: " + id
                + "\nversion: 1.0.0\nschema_version: 1\ndepends: []\n").getBytes());
    }

    private static void writeFile(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static String entryText(ZipFile zip, String entry) throws IOException {
        return new String(zip.getInputStream(zip.getEntry(entry)).readAllBytes());
    }
}
