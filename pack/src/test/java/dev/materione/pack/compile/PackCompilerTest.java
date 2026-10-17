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
                    "type", "minecraft:model",
                    "model", modelRef.contains(":") ? modelRef : id.namespace() + ":" + modelRef)));
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
            // mirrors the real BlockType floor: a block with no item: link
            // offers an auto placement item (the merge drops it when a real
            // item resource owns the id)
            emitter.syntheticItemDefinition(id, Map.of("model", Map.of(
                    "type", "minecraft:model",
                    "model", modelId.toString())));
        }
    }

    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(256);

    @Test
    void compilesFixturePackToStagedZip(@TempDir Path tempDir) throws IOException {
        var outcome = compileFixtures(tempDir);
        assertThat(outcome).isNotNull();
        // ruby + ruby_sword + obsidian_axe + chair display + the auto placement
        // items of marble_stairs and marble_slab (no item: link)
        assertThat(outcome.itemDefinitions()).isEqualTo(7);
        assertThat(outcome.sha256()).isNotBlank();

        try (ZipFile zip = new ZipFile(outcome.stagedZip().toFile())) {
            assertThat(zip.getEntry("pack.mcmeta")).isNotNull();
            assertThat(zip.getEntry("assets/sample/items/ruby_sword.json")).isNotNull();
            assertThat(zip.getEntry("assets/sample/items/obsidian_axe.json")).isNotNull();
            // the chair furniture's display definition (item_model target)
            assertThat(zip.getEntry("assets/sample/items/chair_model.json")).isNotNull();
            // auto placement items: shaped blocks with no item: link get a
            // definition at their own id, referencing the block model
            String stairsPlacement = entryText(zip, "assets/sample/items/marble_stairs.json");
            assertThat(stairsPlacement)
                    .contains("\"type\":\"minecraft:model\"")
                    .contains("\"model\":\"sample:marble_stairs_model\"");
            assertThat(zip.getEntry("assets/sample/items/marble_slab.json")).isNotNull();
            // amethyst_lamp links item: ruby — the linked item owns the definition
            assertThat(zip.getEntry("assets/sample/items/amethyst_lamp.json")).isNull();
            // generated model (auto-model path: texture, no hand-authored model)
            assertThat(zip.getEntry("assets/sample/models/ruby_sword_model.json")).isNotNull();
            // obsidian_axe has a hand-authored model ref — no generated model, and the
            // hand-authored asset is copied verbatim
            assertThat(zip.getEntry("assets/sample/models/obsidian_axe_model.json")).isNotNull();
            assertThat(zip.getEntry("assets/sample/textures/obsidian/axe.png")).isNotNull();

            // atlas coverage: obsidian/ is outside vanilla's item|block scan and
            // gets an exact single source; item/ and block/ are vanilla-scanned
            String atlas = entryText(zip, "assets/minecraft/atlases/blocks.json");
            assertThat(atlas)
                    .contains("\"resource\":\"sample:obsidian/axe\"")
                    .doesNotContain("item/ruby")
                    .doesNotContain("block/marble");

            // items-atlas mirror (#46): 26.x bakes item models from the items
            // atlas, whose vanilla sources gather only item/-convention
            // sprites, and one model may not span two atlases — the generated
            // singles ship in a 26.x-scoped overlay (never the base layer,
            // never the pre-26.x overlays)
            assertThat(zip.getEntry("assets/minecraft/atlases/items.json")).isNull();
            assertThat(zip.getEntry(
                    "materione_1_21_4/assets/minecraft/atlases/items.json")).isNull();
            assertThat(entryText(zip, "materione_items_26/assets/minecraft/atlases/items.json"))
                    .contains("\"resource\":\"sample:obsidian/axe\"")
                    .doesNotContain("item/ruby");

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
                    .contains(String.valueOf(PackFormats.MAX_KNOWN_FORMAT))
                    .contains("materione_items_26");
        }
    }

    @Test
    void datapackMcmetaFoldsTheServerReportedDataFormat(@TempDir Path tempDir) throws IOException {
        // a server newer than the ceiling reporting its own data format: the
        // advertised range widens so the datapack is acceptable to its own server
        var artifact = DatapackWriter.write(
                Map.of("data/t/e.json", Map.of("a", 1)), tempDir, diagnostics,
                new PackFormats.Version(140, 2));
        try (ZipFile zip = new ZipFile(artifact.zip().toFile())) {
            assertThat(entryText(zip, "pack.mcmeta"))
                    .contains("\"min_format\":" + DatapackWriter.DATA_FORMAT_FLOOR)
                    .contains("\"max_format\":[140,2]");
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
    void rootTextureGetsAnExactSingleSource(@TempDir Path tempDir) throws IOException {
        Path packsRoot = tempDir.resolve("packs/atlaswarn");
        writePackSkeleton(packsRoot, "atlaswarn");
        writeFile(packsRoot.resolve("assets/textures/root.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        // texture at the textures/ root — unreachable by directory sources,
        // but the exact single source names it directly
        writeFile(packsRoot.resolve("items/t.yml"), "t:\n  material: STICK\n  texture: root\n".getBytes());

        var outcome = compilePack(tempDir, "atlaswarn");

        assertThat(outcome).isNotNull();
        assertThat(diagnostics.recent(64).stream()
                .filter(d -> "MTR-0017".equals(d.code().code()))
                .findFirst())
                .as("a coverable texture must not raise MTR-0017")
                .isEmpty();
        try (ZipFile zip = new ZipFile(outcome.stagedZip().toFile())) {
            String atlas = entryText(zip, "assets/minecraft/atlases/blocks.json");
            assertThat(atlas).contains("\"resource\":\"atlaswarn:root\"");
        }
    }

    @Test
    void bbmodelSourcesStayOutOfTheClientPack(@TempDir Path tempDir) throws IOException {
        Path packsRoot = tempDir.resolve("packs/bbsrc");
        writePackSkeleton(packsRoot, "bbsrc");
        // the Blockbench save-back layout: the exported model + textures ship,
        // the .bbmodel project source (authoring state) stays in content/packs
        writeFile(packsRoot.resolve("assets/textures/item/gem.png"),
                new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        writeFile(packsRoot.resolve("assets/models/gem.json"),
                "{\"textures\":{\"layer0\":\"bbsrc:item/gem\"}}".getBytes());
        writeFile(packsRoot.resolve("assets/models/source/gem.bbmodel"),
                "{\"meta\":{\"format_version\":\"4.5\"}}".getBytes());

        var outcome = compilePack(tempDir, "bbsrc");

        assertThat(outcome).isNotNull();
        try (ZipFile zip = new ZipFile(outcome.stagedZip().toFile())) {
            assertThat(zip.getEntry("assets/bbsrc/models/gem.json")).isNotNull();
            assertThat(zip.getEntry("assets/bbsrc/textures/item/gem.png")).isNotNull();
            assertThat(zip.getEntry("assets/bbsrc/models/source/gem.bbmodel")).isNull();
            assertThat(zip.getEntry("assets/bbsrc/models/source/")).isNull();
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
            // the user's source survives alongside the generated single source
            assertThat(atlas)
                    .contains("minecraft:block/dirt")
                    .contains("\"resource\":\"minecraft:custom/t\"");
        }
        assertThat(diagnostics.recent(64).stream()
                .filter(d -> d.severity() == Severity.ERROR)
                .findAny())
                .as("no errors compiling the merged-atlas pack")
                .isEmpty();
    }

    @Test
    void syntheticPlacementItemYieldsToASameIdItem(@TempDir Path tempDir) throws IOException {
        Path packsRoot = tempDir.resolve("packs/autoitem");
        writePackSkeleton(packsRoot, "autoitem");
        writeFile(packsRoot.resolve("assets/textures/block/lamp.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        writeFile(packsRoot.resolve("assets/textures/item/lamp.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        // same id in blocks/ and items/, no item: link — the implicit
        // same-id placement facet rides the ITEM's own definition (its
        // hand-authored model makes the two definitions distinguishable)
        writeFile(packsRoot.resolve("blocks/lamp.yml"), "lamp:\n  texture: block/lamp\n".getBytes());
        writeFile(packsRoot.resolve("items/lamp.yml"), "lamp:\n  material: PAPER\n  model: item/lamp_custom\n".getBytes());

        var outcome = compilePack(tempDir, "autoitem",
                AllocationTable.load(tempDir.resolve("storage-autoitem/allocation.bin")),
                List.of(new StubItemType(), new StubBlockType()));

        assertThat(outcome).isNotNull();
        try (ZipFile zip = new ZipFile(outcome.stagedZip().toFile())) {
            // exactly one definition at the shared id — the item's own, never
            // the block's synthetic offer (either compile order)
            String definition = entryText(zip, "assets/autoitem/items/lamp.json");
            assertThat(definition).contains("\"model\":\"autoitem:item/lamp_custom\"");
            assertThat(definition).doesNotContain("autoitem:lamp_model");
        }
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
            GoldenFiles.writeGolden(outcome.stagedDatapackZip(), "m4-sample-datapack.zip");
        }
        GoldenFiles.assertMatchesGolden(outcome.stagedZip(), "m1-sample-pack.zip");
        GoldenFiles.assertMatchesGolden(outcome.stagedDatapackZip(), "m4-sample-datapack.zip");
    }

    @Test
    void mediaOutputsCoverEveryAssetKind(@TempDir Path tempDir) throws IOException {
        var outcome = compileFixtures(tempDir);
        assertThat(outcome.stagedDatapackZip()).isNotNull();
        try (ZipFile zip = new ZipFile(outcome.stagedZip().toFile())) {
            // glyph font: bitmap providers + a reference to the shared shift font
            String font = entryText(zip, "assets/sample/font/glyphs.json");
            assertThat(font)
                    .contains("\"file\":\"sample:gui/heart.png\"")
                    .contains("\"file\":\"sample:gui/spark_1.png\"")
                    // the durable codepoint run starts at 42000 (U+A410), row-major sheet
                    .contains("\"chars\":[\"" + (char) 42000 + (char) 42001 + (char) 42002 + "\",\""
                            + (char) 42003 + (char) 42004 + (char) 42005 + "\"]")
                    .contains("{\"id\":\"materione:shift\",\"type\":\"reference\"}")
                    .doesNotContain("\"type\":\"space\""); // the shift font is shared, not copied
            // unstyled text renders only through minecraft:default — every
            // generated font must be referenced there
            assertThat(entryText(zip, "assets/minecraft/font/default.json"))
                    .contains("{\"id\":\"materione:shift\",\"type\":\"reference\"}")
                    .contains("{\"id\":\"sample:glyphs\",\"type\":\"reference\"}");
            // the shift space provider exists exactly once
            assertThat(entryText(zip, "assets/materione/font/shift.json"))
                    .contains("\"type\":\"space\"")
                    .contains("\"advances\"");
            // per-namespace sounds.json + the vanilla override seam
            assertThat(entryText(zip, "assets/sample/sounds.json"))
                    .contains("\"level_up\":{").contains("\"anthem\":{");
            // no generated painting atlas: vanilla's paintings atlas
            // (atlases/paintings.json) directory-scans textures/painting/
            // across all namespaces with an empty prefix, so the sprite id is
            // <ns>:<path> and a generated single-source file would only ever
            // be an orphan (or a duplicate of vanilla's own sources)
            assertThat(entryNames(zip)).noneMatch(
                    name -> name.startsWith("assets/minecraft/atlases/painting"));
            // text-effect shader ladder: every verified rung carries the
            // replacement text vertex shader + the generated include, never
            // the base layer
            String mcmeta = entryText(zip, "pack.mcmeta");
            assertThat(mcmeta).contains("\"overlays\"");
            for (String dir : new String[] {
                    "materione_1_21_4", "materione_1_21_6", "materione_26_1", "materione_26_2"}) {
                assertThat(entryNames(zip)).anyMatch(name -> name.startsWith(dir + "/"));
            }
            assertThat(entryText(zip, "materione_26_1/assets/minecraft/shaders/core/rendertype_text.vsh"))
                    .contains("#version 330")
                    .contains("vec3 mtrPosition = Position;")
                    .contains("materionePreTransform(mtrPosition);")
                    .contains("materionePostTransform(mtrPosition);")
                    // the attribute is never transformed directly: GLSL
                    // rejects assignment to an `in` and the failed compile
                    // makes the client drop the whole pack (#39)
                    .doesNotContain("vec4(Position, 1.0)");
            String effects = entryText(zip,
                    "materione_26_1/assets/minecraft/shaders/include/materione_text_effects.glsl");
            assertThat(effects)
                    .contains("void materionePreTransform(inout vec3 pos)")
                    .contains("void materionePostTransform(in vec3 pos)")
                    // the fixture's two effects: rainbow (with baked stops) and wave
                    .contains("vec3(1.0, 0.0, 0.99607843)")
                    .contains("vec3(1.0, 0.0, 0.99215686)")
                    .contains("vertexColor.rgb = (mt < ")
                    .contains("pos.y += sin(")
                    // the hooks must never touch the Position attribute
                    .doesNotContain("Position");
            assertThat(mcmeta).contains("ignored_shaders");
        }
        try (ZipFile datapack = new ZipFile(outcome.stagedDatapackZip().toFile())) {
            assertThat(entryText(datapack, "data/sample/painting_variant/sunrise.json"))
                    .contains("\"asset_id\":\"sample:sunrise\"")
                    .contains("\"width\":2");
            assertThat(entryText(datapack, "data/sample/jukebox_song/anthem.json"))
                    .contains("\"sound_id\":\"sample:anthem\"")
                    .contains("\"comparator_output\":7");
            assertThat(entryText(datapack, "data/minecraft/tags/painting_variant/placeable.json"))
                    .contains("sample:sunrise");
            // the datapack advertises its range in BOTH mcmeta generations, like
            // the resource pack — a single old pack_format reads as incompatible
            // on 1.21.9+/26.x servers and its content silently never loads
            String mcmeta = entryText(datapack, "pack.mcmeta");
            assertThat(mcmeta)
                    .contains("\"pack_format\":" + DatapackWriter.DATA_FORMAT_FLOOR)
                    .contains("supported_formats")
                    .contains("\"min_format\":" + DatapackWriter.DATA_FORMAT_FLOOR)
                    .contains("\"max_format\":" + DatapackWriter.DATA_FORMAT_CEILING.toMcmeta());
        }
    }

    @Test
    void allocationPersistsBeforeOutputs(@TempDir Path tempDir) throws IOException {
        Path allocationFile = tempDir.resolve("storage/allocation.bin");
        var allocation = AllocationTable.load(allocationFile);
        compileFixtures(tempDir, allocation);
        // the lamp's state slot flushed to disk before the pack was written
        assertThat(Files.exists(allocationFile)).isTrue();
        // 1 block state slot + 4 glyph runs (sheet 6 codepoints, color sheet 4,
        // heart 1, frames 2) + 2 effect signals (rainbow, wave)
        assertThat(allocation.liveCount()).isEqualTo(7);
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
                // the REAL content types: the golden locks actual compile
                // behavior (state allocation, shaped blockstates, generated
                // display models, glyph font runs, sound events, painting
                // variants) — never a test double
                List.of(new dev.materione.content.items.ItemType(),
                        new BlockType(),
                        new dev.materione.content.furniture.FurnitureType(),
                        new dev.materione.content.glyphs.GlyphType(),
                        new dev.materione.content.sounds.SoundType(),
                        new dev.materione.content.paintings.PaintingType(),
                        new dev.materione.content.effects.EffectType()),
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

    private static java.util.stream.Stream<String> entryNames(ZipFile zip) {
        return zip.stream().map(java.util.zip.ZipEntry::getName);
    }
}
