package dev.materione.content.blocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.core.blocks.BlockStateSpaces;
import dev.materione.core.blocks.BlockStateSpaces.Technique;
import dev.materione.core.content.Allocations;
import dev.materione.core.content.CompileRejectException;
import dev.materione.core.diagnostics.DiagnosticCodes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BlockTypeTest {

    private final BlockType type = new BlockType();
    private final NamespacedId id = NamespacedId.of("sample", "lamp");
    /** Records emitted vanilla files: path → fragment. */
    private final Map<String, Map<String, Object>> vanilla = new LinkedHashMap<>();
    private final List<String> models = new java.util.ArrayList<>();
    private final Allocations allocations = new Allocations() {
        private int next = 1;

        @Override
        public String allocate(NamespacedId resource, String kind) {
            return Integer.toString(next++);
        }

        @Override
        public Optional<String> lookup(NamespacedId resource, String kind) {
            return Optional.empty();
        }
    };

    @Test
    void schemaDescribesTheAuthoringSurface() {
        var description = type.describe();
        assertThat(type.name()).isEqualTo("block");
        assertThat(type.directory()).isEqualTo("blocks");
        assertThat(description.parameters())
                .extracting(ParameterSchema::id)
                .contains("technique", "host", "texture", "model", "drops", "move_policy", "blast_resistant");
        assertThat(description.parameters())
                .allSatisfy(schema -> assertThat(schema.type()).isNotNull());
    }

    @Test
    void parsesTheDefaultTechniqueAndGeneratedModelPath() {
        Optional<Object> parsed = type.parse(id, Map.of(
                "texture", "block/amethyst_lamp",
                "blast_resistant", true), Map.of());

        assertThat(parsed).isPresent();
        BlockDefinition block = (BlockDefinition) parsed.get();
        assertThat(block.technique()).isEqualTo(Technique.NOTE_BLOCK);
        assertThat(block.host()).isNull(); // derived per technique at compile
        assertThat(block.blastResistant()).isTrue();
        assertThat(block.wantsGeneratedModel()).isTrue();
    }

    @Test
    void technicalBlocksRequireAnExplicitHost() {
        assertThat(type.parse(id, Map.of(
                "technique", "technical", "texture", "block/base"), Map.of())).isEmpty();
        assertThat(type.parse(id, Map.of(
                "technique", "technical", "host", "mushroom_stem", "texture", "block/base"), Map.of()))
                .isPresent();
    }

    @Test
    void rejectsUnknownTechniquesMovePoliciesAndRenderlessBlocks() {
        assertThat(type.parse(id, Map.of("technique", "levitation", "texture", "t"), Map.of())).isEmpty();
        assertThat(type.parse(id, Map.of("move_policy", "teleport", "texture", "t"), Map.of())).isEmpty();
        assertThat(type.parse(id, Map.of("display_name", "no appearance"), Map.of())).isEmpty();
    }

    @Test
    void compilesToAModelAndOneBlockstateVariantPerAllocatedSlot() {
        Object block = type.parse(id, Map.of("texture", "block/lamp"), Map.of()).orElseThrow();

        type.compile(block, NamespacedId.of("sample", "lamp_model"), emitterOf(), allocations);

        assertThat(models).containsExactly("sample:lamp_model");
        assertThat(vanilla).containsOnlyKeys("blockstates/note_block.json");
        @SuppressWarnings("unchecked")
        var variants = (Map<String, Object>) vanilla.get("blockstates/note_block.json").get("variants");
        assertThat(variants).containsOnlyKeys("instrument=harp,note=1,powered=false");
        @SuppressWarnings("unchecked")
        var entry = (Map<String, Object>) variants.get("instrument=harp,note=1,powered=false");
        assertThat(entry.get("model")).isEqualTo("sample:lamp_model");
    }

    @Test
    void stringBlocksReserveTheVanillaWireStateAsInvisible() {
        Object block = type.parse(NamespacedId.of("sample", "grass"),
                Map.of("technique", "string", "texture", "block/grass"), Map.of()).orElseThrow();

        type.compile(block, NamespacedId.of("sample", "grass_model"), emitterOf(), allocations);

        assertThat(vanilla).containsOnlyKeys("blockstates/tripwire.json");
        @SuppressWarnings("unchecked")
        var variants = (Map<String, Object>) vanilla.get("blockstates/tripwire.json").get("variants");
        // the allocated slot plus the reserved all-false barrier entry
        assertThat(variants).hasSize(2);
        assertThat(variants).containsKey(BlockStateSpaces.tripwireReservedKey());
    }

    @Test
    void handAuthoredModelsSkipGenerationButStillClaimTheirSlot() {
        Object block = type.parse(id, Map.of("model", "fancy/lamp"), Map.of()).orElseThrow();

        type.compile(block, NamespacedId.of("sample", "lamp_model"), emitterOf(), allocations);

        assertThat(models).isEmpty();
        assertThat(vanilla).containsOnlyKeys("blockstates/note_block.json");
    }

    @Test
    void exhaustionRejectsWithMtr0019InsteadOfCrashing() {
        Allocations exhausted = new Allocations() {
            @Override
            public String allocate(NamespacedId resource, String kind) {
                return "999999"; // beyond every technique space
            }

            @Override
            public Optional<String> lookup(NamespacedId resource, String kind) {
                return Optional.empty();
            }
        };
        Object block = type.parse(id, Map.of("texture", "block/lamp"), Map.of()).orElseThrow();

        assertThatThrownBy(() ->
                type.compile(block, NamespacedId.of("sample", "lamp_model"), emitterOf(), exhausted))
                .isInstanceOfSatisfying(CompileRejectException.class, rejected -> {
                    assertThat(rejected.code().code()).isEqualTo("MTR-0019");
                    assertThat(rejected.code()).isEqualTo(DiagnosticCodes.BLOCK_STATE_EXHAUSTED);
                    assertThat(rejected.hint()).isNotBlank();
                });
    }

    @Test
    void templateInheritanceResolvesWithDefinitionWins() {
        var templates = Map.of(
                "sample:base", Map.<String, Object>of("technique", "chorus", "blast_resistant", true));
        Optional<Object> parsed = type.parse(id, Map.of(
                "template", "base", "texture", "block/lamp"), templates);

        assertThat(parsed).isPresent();
        BlockDefinition block = (BlockDefinition) parsed.get();
        assertThat(block.technique()).isEqualTo(Technique.CHORUS); // inherited
        assertThat(block.texture()).isEqualTo("block/lamp");       // definition wins
    }

    @Test
    void parsesThePlacementLinkAndBehaviorSection() {
        Optional<Object> parsed = type.parse(id, Map.of(
                "texture", "block/lamp",
                "item", "lamp_item",
                "behavior", List.of(
                        Map.<String, Object>of(
                                "on", "interact",
                                "do", List.<Object>of(Map.<String, Object>of("type", "message",
                                        "text", "hi"))))),
                Map.of());

        assertThat(parsed).isPresent();
        BlockDefinition block = (BlockDefinition) parsed.get();
        assertThat(block.placedBy()).isEqualTo(NamespacedId.of("sample", "lamp_item"));
        assertThat(block.behavior()).hasSize(1);
    }

    @Test
    void behaviorAndItemFieldsAreOptionalAndFailSoft() {
        Optional<Object> parsed = type.parse(id, Map.of("texture", "block/lamp"), Map.of());
        assertThat(parsed).isPresent();
        BlockDefinition block = (BlockDefinition) parsed.get();
        assertThat(block.placedBy()).isNull();
        assertThat(block.behavior()).isEmpty();

        // non-list behavior shapes degrade to empty rather than rejecting the block
        Optional<Object> odd = type.parse(id,
                Map.of("texture", "block/lamp", "behavior", "oops"), Map.of());
        assertThat(odd).isPresent();
        assertThat(((BlockDefinition) odd.get()).behavior()).isEmpty();
    }

    /**
     * Shaped stairs (M2 D7): every host state is claimed with one straight
     * model, rotated like vanilla's own stair blockstates — top halves flip
     * x by 180, facings cycle y 0/90/180/270.
     */
    @Test
    void stairsClaimEveryHostStateWithVanillaRotations() {
        Object block = type.parse(NamespacedId.of("sample", "marble_stairs"),
                Map.of("technique", "stairs", "texture", "block/marble"), Map.of()).orElseThrow();

        type.compile(block, NamespacedId.of("sample", "marble_stairs_model"), emitterOf(), allocations);

        // no allocation: identity rides chunk PDC
        assertThat(vanilla).containsOnlyKeys("blockstates/waxed_cut_copper_stairs.json");
        @SuppressWarnings("unchecked")
        var variants = (Map<String, Object>) vanilla
                .get("blockstates/waxed_cut_copper_stairs.json").get("variants");
        // facing × half × shape = 4 × 2 × 5 — corner shapes render straight
        assertThat(variants).hasSize(40);
        assertThat(variants).containsKey("facing=east,half=bottom,shape=straight");
        assertThat(variants).containsKey("facing=north,half=top,shape=inner_left");
        @SuppressWarnings("unchecked")
        var eastBottom = (Map<String, Object>) variants.get("facing=east,half=bottom,shape=straight");
        assertThat(eastBottom).containsEntry("model", "sample:marble_stairs_model");
        assertThat(eastBottom).doesNotContainKey("y");
        @SuppressWarnings("unchecked")
        var northTop = (Map<String, Object>) variants.get("facing=north,half=top,shape=straight");
        assertThat(northTop).containsEntry("x", 180);
        assertThat(northTop).containsEntry("y", 270);
    }

    @Test
    void slabsEmitThreeModelsAndClaimAllHostTypes() {
        Object block = type.parse(NamespacedId.of("sample", "marble_slab"),
                Map.of("technique", "slab", "texture", "block/marble"), Map.of()).orElseThrow();

        type.compile(block, NamespacedId.of("sample", "marble_slab_model"), emitterOf(), allocations);

        assertThat(models).containsExactly(
                "sample:marble_slab_model",
                "sample:marble_slab_model_top",
                "sample:marble_slab_model_double");
        assertThat(vanilla).containsOnlyKeys("blockstates/waxed_cut_copper_slab.json");
        @SuppressWarnings("unchecked")
        var variants = (Map<String, Object>) vanilla
                .get("blockstates/waxed_cut_copper_slab.json").get("variants");
        assertThat(variants).containsOnlyKeys("type=bottom", "type=top", "type=double");
        assertThat(variants.get("type=bottom")).isEqualTo(Map.of("model", "sample:marble_slab_model"));
        assertThat(variants.get("type=top"))
                .isEqualTo(Map.of("model", "sample:marble_slab_model_top"));
        assertThat(variants.get("type=double"))
                .isEqualTo(Map.of("model", "sample:marble_slab_model_double"));
    }

    private PackEmitterOf emitterOf() {
        return new PackEmitterOf();
    }

    /** Collector mirroring the compiler's Emitter for the surfaces blocks touch. */
    private final class PackEmitterOf implements dev.materione.core.content.PackEmitter {
        @Override
        public void itemDefinition(NamespacedId itemId, Map<String, Object> itemModelJson) {}

        @Override
        public void model(NamespacedId modelId, Map<String, Object> modelJson) {
            models.add(modelId.toString());
        }

        @Override
        public void copyAsset(java.nio.file.Path sourceFile, String destination) {}

        @Override
        public void vanillaFile(String path, Map<String, Object> json) {
            mergeInto(vanilla.computeIfAbsent(path, key -> new LinkedHashMap<>()), json);
        }

        /** Mirrors the compiler's fragment merge (writable copies on descend). */
        @SuppressWarnings("unchecked")
        private static void mergeInto(Map<String, Object> dst, Map<String, Object> src) {
            for (var entry : src.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> && dst.get(entry.getKey()) instanceof Map<?, ?>) {
                    var child = new LinkedHashMap<String, Object>((Map<String, Object>) dst.get(entry.getKey()));
                    mergeInto(child, (Map<String, Object>) entry.getValue());
                    dst.put(entry.getKey(), child);
                } else {
                    dst.put(entry.getKey(), entry.getValue());
                }
            }
        }

        @Override
        public void description(String text) {}
    }
}
