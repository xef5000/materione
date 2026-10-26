package dev.materione.content.furniture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.ParameterSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Furniture authoring surface: parse discipline and model emission. */
class FurnitureTypeTest {

    private final FurnitureType type = new FurnitureType();
    private final NamespacedId id = NamespacedId.of("sample", "chair");

    @Test
    void schemaDescribesTheAuthoringSurface() {
        var description = type.describe();
        assertThat(type.name()).isEqualTo("furniture");
        assertThat(type.directory()).isEqualTo("furnitures");
        assertThat(description.parameters())
                .extracting(ParameterSchema::id)
                .contains("model", "texture", "hitboxes", "seats", "lights", "storage",
                        "rotation", "evolution", "item", "behavior");
    }

    @Test
    void parsesTheFullChairSurface() {
        Optional<Object> parsed = type.parse(id, Map.of(
                "texture", "block/marble",
                "display_name", "Chair",
                "rotation", 45,
                "hitboxes", List.of(Map.<String, Object>of(
                        "type", "interaction", "offset", List.of(0, 0, 0),
                        "width", 1.0, "height", 1.0)),
                "seats", List.of(Map.<String, Object>of("offset", List.of(0, 0.3, 0))),
                "storage", 27,
                "label", "&eChair",
                "evolution", List.of(Map.<String, Object>of(
                        "target", "old_chair", "after", 3600)),
                "item", "ruby"), Map.of());

        assertThat(parsed).isPresent();
        FurnitureDefinition furniture = (FurnitureDefinition) parsed.get();
        assertThat(furniture.rotationSnap()).isEqualTo(45.0);
        assertThat(furniture.hitboxes()).hasSize(1);
        assertThat(furniture.hitboxes().get(0).isBarrier()).isFalse();
        assertThat(furniture.seats()).hasSize(1);
        assertThat(furniture.storage()).isEqualTo(27);
        assertThat(furniture.label()).isEqualTo("&eChair");
        assertThat(furniture.evolution()).hasSize(1);
        assertThat(furniture.evolution().get(0).target())
                .isEqualTo(NamespacedId.of("sample", "old_chair"));
        assertThat(furniture.placedBy()).isEqualTo(NamespacedId.of("sample", "ruby"));
    }

    @Test
    void rejectsImpossibleRotationsStorageAndHitboxes() {
        var base = Map.<String, Object>of("texture", "block/marble");

        // rotation snap outside {0, 45, 90, 180}
        assertThat(type.parse(id, Map.of("texture", "block/marble", "rotation", 30), Map.of()))
                .isEmpty();
        // storage must be a multiple of 9 up to 54
        assertThat(type.parse(id, Map.of("texture", "block/marble", "storage", 10), Map.of()))
                .isEmpty();
        assertThat(type.parse(id, Map.of("texture", "block/marble", "storage", 63), Map.of()))
                .isEmpty();
        // hitbox types are interaction|barrier
        assertThat(type.parse(id, Map.of(
                "texture", "block/marble",
                "hitboxes", List.of(Map.<String, Object>of("type", "ghost"))), Map.of()))
                .isEmpty();
        // light levels live in 1..15
        assertThat(type.parse(id, Map.of(
                "texture", "block/marble",
                "lights", List.of(Map.<String, Object>of("offset", List.of(0, 1, 0), "level", 16))),
                Map.of()))
                .isEmpty();
        // nothing to render at all
        assertThat(type.parse(id, Map.of("display_name", "invisible"), Map.of())).isEmpty();
    }

    @Test
    void compileEmitsTheGeneratedModelPlusItsDisplayDefinition() {
        Object chair = type.parse(id, Map.of("texture", "block/marble"), Map.of()).orElseThrow();
        var models = new java.util.ArrayList<String>();
        var definitions = new java.util.LinkedHashMap<NamespacedId, Map<String, Object>>();
        var vanilla = new java.util.LinkedHashMap<String, Map<String, Object>>();
        dev.materione.api.content.PackEmitter emitter = new dev.materione.api.content.PackEmitter() {
            @Override
            public void itemDefinition(NamespacedId itemId, Map<String, Object> itemModelJson) {
                definitions.put(itemId, itemModelJson);
            }

            @Override
            public void model(NamespacedId modelId, Map<String, Object> modelJson) {
                models.add(modelId.toString());
            }

            @Override
            public void copyAsset(java.nio.file.Path sourceFile, String destination) {}

            @Override
            public void vanillaFile(String path, Map<String, Object> json) {
                vanilla.put(path, json);
            }

            @Override
            public void description(String text) {}
        };

        type.compile(chair, NamespacedId.of("sample", "chair_model"), emitter,
                new dev.materione.api.content.Allocations() {
                    @Override
                    public String allocate(NamespacedId resource, String kind) {
                        throw new IllegalStateException("furniture allocates nothing");
                    }

                    @Override
                    public java.util.Optional<String> lookup(NamespacedId resource, String kind) {
                        return java.util.Optional.empty();
                    }
                });

        assertThat(models).containsExactly("sample:chair_model");
        assertThat(vanilla).isEmpty(); // no blockstate surface for entity-carried furniture
        // the cluster's ItemDisplay carries item_model: sample:chair_model — the
        // definition at that id is what makes the display resolve at all
        assertThat(definitions).containsOnlyKeys(NamespacedId.of("sample", "chair_model"));
        @SuppressWarnings("unchecked")
        var reference = (Map<String, Object>) definitions.get(NamespacedId.of("sample", "chair_model")).get("model");
        assertThat(reference).containsEntry("type", "minecraft:model")
                .containsEntry("model", "sample:chair_model");
    }

    @Test
    void handAuthoredModelsGetADefinitionAtTheirOwnRef() {
        Object chair = type.parse(id, Map.of("model", "sample:block/chair_custom"), Map.of())
                .orElseThrow();
        var models = new java.util.ArrayList<String>();
        var definitions = new java.util.LinkedHashMap<NamespacedId, Map<String, Object>>();
        type.compile(chair, NamespacedId.of("sample", "chair_model"), capturingEmitter(models, definitions),
                noAllocations());

        assertThat(models).isEmpty(); // nothing generated
        assertThat(definitions).containsOnlyKeys(NamespacedId.of("sample", "block/chair_custom"));
        @SuppressWarnings("unchecked")
        var reference = (Map<String, Object>)
                definitions.get(NamespacedId.of("sample", "block/chair_custom")).get("model");
        assertThat(reference).containsEntry("model", "sample:block/chair_custom");
    }

    @Test
    void presentationCarriesExactlyTheModelCompileEmits() {
        Object chair = type.parse(id, Map.of("texture", "block/marble"), Map.of()).orElseThrow();
        var emitted = new java.util.LinkedHashMap<String, Map<String, Object>>();
        type.compile(chair, NamespacedId.of("sample", "chair_model"), new dev.materione.api.content.PackEmitter() {
            @Override
            public void itemDefinition(NamespacedId itemId, Map<String, Object> itemModelJson) {}

            @Override
            public void model(NamespacedId modelId, Map<String, Object> modelJson) {
                emitted.put(modelId.toString(), modelJson);
            }

            @Override
            public void copyAsset(java.nio.file.Path sourceFile, String destination) {}

            @Override
            public void vanillaFile(String path, Map<String, Object> json) {}

            @Override
            public void description(String text) {}
        }, noAllocations());

        assertThat(type.presentation(chair).orElseThrow().generatedModel())
                .isEqualTo(emitted.get("sample:chair_model"))
                .containsEntry("parent", "block/cube_all");
    }

    // --- helpers -------------------------------------------------------------

    private static dev.materione.api.content.PackEmitter capturingEmitter(
            List<String> models, Map<NamespacedId, Map<String, Object>> definitions) {
        return new dev.materione.api.content.PackEmitter() {
            @Override
            public void itemDefinition(NamespacedId itemId, Map<String, Object> itemModelJson) {
                definitions.put(itemId, itemModelJson);
            }

            @Override
            public void model(NamespacedId modelId, Map<String, Object> modelJson) {
                models.add(modelId.toString());
            }

            @Override
            public void copyAsset(java.nio.file.Path sourceFile, String destination) {}

            @Override
            public void vanillaFile(String path, Map<String, Object> json) {}

            @Override
            public void description(String text) {}
        };
    }

    private static dev.materione.api.content.Allocations noAllocations() {
        return new dev.materione.api.content.Allocations() {
            @Override
            public String allocate(NamespacedId resource, String kind) {
                throw new IllegalStateException("furniture allocates nothing");
            }

            @Override
            public java.util.Optional<String> lookup(NamespacedId resource, String kind) {
                return java.util.Optional.empty();
            }
        };
    }
}
