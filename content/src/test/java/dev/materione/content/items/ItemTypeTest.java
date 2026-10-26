package dev.materione.content.items;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.PackEmitter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Item type behavior: template resolution, self-description, compilation. */
class ItemTypeTest {

    private final ItemType type = new ItemType();

    @Test
    void parsesAndResolvesTemplatesWithOverride() {
        var templates = new LinkedHashMap<String, Map<String, Object>>();
        templates.put("sample:base_item", map("material", "PAPER", "model_parent", "item/handheld"));
        templates.put("sample:hard_base", map("template", "sample:base_item", "material", "STONE"));

        var definition = map(
                "template", "sample:hard_base",
                "material", "NETHERITE_SWORD",
                "texture", "obsidian/axe");
        var parsed = type.parse(NamespacedId.parse("sample:axe"), definition, templates);
        assertThat(parsed).isPresent();
        var item = (ItemDefinition) parsed.get();
        // template chain merged, definition's own fields win
        assertThat(item.material()).isEqualTo("NETHERITE_SWORD");
        assertThat(item.modelParent()).isEqualTo("item/handheld"); // inherited from base
        assertThat(item.wantsGeneratedModel()).isTrue();
    }

    @Test
    void shortFormTemplateRefResolvesAgainstOwnNamespace() {
        // fixture shape: `template: base_item` with pool keys `sample:base_item`
        var templates = new LinkedHashMap<String, Map<String, Object>>();
        templates.put("sample:base_item", map("material", "PAPER"));
        var parsed = type.parse(
                NamespacedId.parse("sample:thing"),
                map("template", "base_item", "material", "STONE"),
                templates);
        assertThat(parsed).isPresent();
        assertThat(((ItemDefinition) parsed.get()).material()).isEqualTo("STONE");
    }

    @Test
    void diamondInheritanceIsRejected() {
        var templates = new LinkedHashMap<String, Map<String, Object>>();
        templates.put("sample:a", map("template", "sample:b", "material", "DIRT"));
        templates.put("sample:b", map("template", "sample:a", "material", "GRASS"));
        var parsed = type.parse(
                NamespacedId.parse("sample:x"),
                map("template", "sample:a", "material", "STONE"),
                templates);
        assertThat(parsed).isEmpty();
    }

    @Test
    void missingMaterialIsRejected() {
        assertThat(type.parse(
                NamespacedId.parse("sample:x"),
                map("display_name", "No material"),
                Map.of())).isEmpty();
    }

    @Test
    void unknownTemplateIsRejected() {
        assertThat(type.parse(
                NamespacedId.parse("sample:x"),
                map("template", "sample:absent", "material", "STONE"),
                Map.of())).isEmpty();
    }

    @Test
    void selfDescriptionIsTheSchemaSource() {
        var description = type.describe();
        assertThat(description.displayName()).isEqualTo("Item");
        assertThat(description.parameters()).isNotEmpty();
        assertThat(description.parameters())
                .anySatisfy(p -> assertThat(p.id()).isEqualTo("material"));
        assertThat(description.parameters())
                .anySatisfy(p -> assertThat(p.id()).isEqualTo("mechanics"));
    }

    @Test
    void displayNameAndLoreDeclareFormattingWidgetsInTheRegistrySchema() {
        var parameters = type.describe().parameters();
        var displayName = parameters.stream()
                .filter(parameter -> parameter.id().equals("display_name"))
                .findFirst().orElseThrow();
        var lore = parameters.stream()
                .filter(parameter -> parameter.id().equals("lore"))
                .findFirst().orElseThrow();

        assertThat(displayName.uiHint()).isEqualTo("formatted-text");
        assertThat(displayName.editor().label()).isEqualTo("Display name");
        assertThat(displayName.editor().section()).isEqualTo("identity");
        assertThat(displayName.editor().order()).isZero();
        assertThat(displayName.editor().example()).isEqualTo("Ruby sword");
        assertThat(lore.uiHint()).isEqualTo("formatted-lines");
        assertThat(lore.editor().label()).isEqualTo("Lore");
        assertThat(lore.editor().section()).isEqualTo("gameplay");
        assertThat(lore.editor().order()).isEqualTo(10);
        assertThat(lore.editor().example()).isEqualTo("A very special item");
    }

    @Test
    void materialChoicesComeOnlyFromTheProvidedPlatformPalette() {
        var field = new ItemType(List.of("STICK", "PAPER", "STICK")).describe().parameters().get(0);

        assertThat(field.id()).isEqualTo("material");
        assertThat(field.type()).isEqualTo(dev.materione.api.registry.ParameterType.ENUM);
        assertThat(field.allowedValues()).containsExactly("PAPER", "STICK");
        assertThat(field.uiHint()).isEqualTo("material");
    }

    @Test
    void unavailableMaterialPaletteDoesNotInventAnEnum() {
        var field = new ItemType(List.of()).describe().parameters().get(0);

        assertThat(field.type()).isEqualTo(dev.materione.api.registry.ParameterType.STRING);
        assertThat(field.allowedValues()).isEmpty();
        assertThat(field.uiHint()).isEqualTo("material");
    }

    @Test
    void presentationUsesTheSameTemplateResolvedItemParserAsCompilation() {
        var templates = new LinkedHashMap<String, Map<String, Object>>();
        templates.put("sample:base_item",
                map("display_name", "Inherited name", "material", "PAPER", "texture", "item/base"));
        var parsed = type.parse(NamespacedId.parse("sample:thing"),
                map("template", "base_item"), templates).orElseThrow();

        var presentation = type.presentation(parsed).orElseThrow();

        assertThat(presentation.name()).isEqualTo("Inherited name");
        assertThat(presentation.material()).isEqualTo("PAPER");
        assertThat(presentation.texture()).isEqualTo("item/base");
    }

    @Test
    void parsesMechanicsDeclarations() {
        var definition = map(
                "material", "NETHERITE_SWORD",
                "mechanics", List.of(
                        map("soulbound", Map.of()),
                        map("durability", map("max", 500)),
                        map("food", map("nutrition", 6, "saturation", 8.0))));
        var parsed = type.parse(NamespacedId.parse("sample:talisman"), definition, Map.of());
        assertThat(parsed).isPresent();
        var item = (ItemDefinition) parsed.get();
        assertThat(item.mechanics()).hasSize(3);
        assertThat(item.mechanics().get(0)).containsEntry("soulbound", Map.of());
        assertThat(item.mechanics().get(1)).containsKey("durability");
    }

    @Test
    void missingMechanicsYieldsEmptyList() {
        var parsed = type.parse(
                NamespacedId.parse("sample:plain"), map("material", "PAPER"), Map.of());
        assertThat(parsed).isPresent();
        assertThat(((ItemDefinition) parsed.get()).mechanics()).isEmpty();
    }

    @Test
    void compileEmitsGeneratedModelAndItemDefinition() {
        var templates = new LinkedHashMap<String, Map<String, Object>>();
        templates.put("sample:base_item", map("model_parent", "minecraft:item/handheld"));
        var parsed = type.parse(
                NamespacedId.parse("sample:ruby_sword"),
                map("template", "sample:base_item", "material", "NETHERITE_SWORD", "texture", "item/ruby_sword"),
                templates);
        var emitter = new RecordingEmitter();
        type.compile(parsed.orElseThrow(),
                NamespacedId.parse("sample:ruby_sword_model"), emitter, NO_ALLOCATIONS);

        assertThat(emitter.models).hasSize(1);
        var model = emitter.models.get(0);
        assertThat(model.json()).containsEntry("parent", "minecraft:item/handheld");
        assertThat(model.json().get("textures"))
                .isEqualTo(map("layer0", "sample:item/ruby_sword"));

        assertThat(emitter.itemDefinitions).hasSize(1);
        var definition = emitter.itemDefinitions.get(0);
        assertThat(definition.id().toString()).isEqualTo("sample:ruby_sword");
        var reference = cast(definition.json().get("model"));
        // 1.21.4+ item model definition: the basic single-model case
        assertThat(reference).containsEntry("type", "minecraft:model");
        assertThat(reference).containsEntry("model", "sample:ruby_sword_model");
    }

    @Test
    void compileWithHandAuthoredModelReferencesItVerbatim() {
        var parsed = type.parse(
                NamespacedId.parse("sample:axe"),
                map("material", "IRON_AXE", "model", "sample:models/custom"),
                Map.of());
        var emitter = new RecordingEmitter();
        type.compile(parsed.orElseThrow(), NamespacedId.parse("sample:axe_model"), emitter, NO_ALLOCATIONS);
        assertThat(emitter.models).isEmpty(); // nothing generated
        var reference = cast(emitter.itemDefinitions.get(0).json().get("model"));
        assertThat(reference).containsEntry("type", "minecraft:model");
        assertThat(reference).containsEntry("model", "sample:models/custom");
    }

    @Test
    void materialOnlyItemEmitsNothing() {
        var parsed = type.parse(
                NamespacedId.parse("sample:plain"),
                map("material", "PAPER"),
                Map.of());
        var emitter = new RecordingEmitter();
        type.compile(parsed.orElseThrow(), NamespacedId.parse("sample:plain_model"), emitter, NO_ALLOCATIONS);
        assertThat(emitter.models).isEmpty();
        assertThat(emitter.itemDefinitions).isEmpty();
    }

    // --- helpers -------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> map(Object... pairs) {
        var out = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private record Emitted(NamespacedId id, Map<String, Object> json) {}

    /** Items allocate nothing; a rejecting stub keeps accidental allocation loud. */
    private static final dev.materione.api.content.Allocations NO_ALLOCATIONS =
            new dev.materione.api.content.Allocations() {
                @Override
                public String allocate(NamespacedId resource, String kind) {
                    throw new IllegalStateException("item compilation must not allocate");
                }

                @Override
                public java.util.Optional<String> lookup(NamespacedId resource, String kind) {
                    return java.util.Optional.empty();
                }
            };

    private static final class RecordingEmitter implements PackEmitter {
        final List<Emitted> itemDefinitions = new ArrayList<>();
        final List<Emitted> models = new ArrayList<>();

        @Override
        public void itemDefinition(dev.materione.api.NamespacedId id, Map<String, Object> json) {
            itemDefinitions.add(new Emitted(id, json));
        }

        @Override
        public void model(dev.materione.api.NamespacedId id, Map<String, Object> json) {
            models.add(new Emitted(id, json));
        }

        @Override
        public void copyAsset(Path sourceFile, String destination) {}

        @Override
        public void vanillaFile(String path, Map<String, Object> json) {
            throw new IllegalStateException("items must not emit vanilla-namespace files");
        }

        @Override
        public void description(String text) {}
    }
}
