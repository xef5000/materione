package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.content.blocks.BlockType;
import dev.materione.content.furniture.FurnitureType;
import dev.materione.content.items.ItemType;
import dev.materione.content.recipes.RecipeType;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.document.Scaffolds;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Scaffolds are hand-curated, which means they can drift from the registry
 * schemas they teach. This test is the pin: every scaffold must parse
 * losslessly AND carry a line for each required parameter of its content
 * type's self-description — so a schema change that forgets the scaffold
 * breaks CI here instead of handing users a starter that cannot validate.
 */
class ScaffoldSchemaConsistencyTest {

    @Test
    void everyScaffoldParsesAndCoversRequiredParameters() {
        check("items", "ruby_amulet", new ItemType());
        check("blocks", "sunset_lamp", new BlockType());
        check("furnitures", "garden_bench", new FurnitureType());
        check("recipes", "ruby_amulet_recipe", new RecipeType());
        check("glyphs", "harness_heart", new dev.materione.content.glyphs.GlyphType());
        check("sounds", "level_jingle", new dev.materione.content.sounds.SoundType());
        check("paintings", "sunset_wall", new dev.materione.content.paintings.PaintingType());
        check("effects", "ruby_wave", new dev.materione.content.effects.EffectType());
        check("guis", "shop_main", guiType());
        // The web copy of the New GUI starter (the demo recording reads it) must match byte for byte.
        assertThat(dev.materione.web.rpc.ops.Scaffolds.forPath("guis/shop_main.yml", null))
                .isEqualTo(Scaffolds.forPath("guis/shop_main.yml", null));
        // Templates have no registry type — parse-cleanliness only.
        assertParses(Scaffolds.forPath("templates/base_pack.yml", null), "base_pack");
        // ... but a template that serves a type may only start with fields that type declares.
        checkTemplateStarter("items", "base_blade", new ItemType());
        checkTemplateStarter("blocks", "base_lamp", new BlockType());
    }

    /** The New GUI starter is a valid three-row chest: it parses with every layer accepted and no diagnostics. */
    @Test
    void newGuiStarterParsesWithEveryLayerAccepted() {
        var gui = guiType();
        @SuppressWarnings("unchecked")
        Map<String, Object> starter = (Map<String, Object>) LosslessYaml.parse(
                Scaffolds.forPath("guis/shop_main.yml", null)).data().get("shop_main");
        var parsed = gui.parse(dev.materione.api.NamespacedId.of("materione", "shop_main"), starter, Map.of());
        assertThat(parsed).isPresent();
        var definition = (dev.materione.content.gui.GuiDefinition) parsed.get();
        assertThat(definition.rows()).isEqualTo(3);
        assertThat(definition.layers()).hasSize(2);
        assertThat(definition.skipped()).isEmpty();
        assertThat(gui.diagnose(definition)).isEmpty();
        // panel is 176 wide and 114 + rows * 18 tall, the vanilla chest canvas
        var panel = (dev.materione.content.gui.PanelComponent.Layer) definition.layers().get(0).compiled();
        assertThat(panel.width()).isEqualTo(176);
        assertThat(panel.height()).isEqualTo(114 + definition.rows() * 18);
    }

    /** The GUI type with the built-in components registered, as bootstrap registers them. */
    private static dev.materione.content.gui.GuiType guiType() {
        var registries = new dev.materione.core.registry.RegistryService();
        var components = registries.registry(
                dev.materione.api.registry.RegistryKeys.GUI_COMPONENTS,
                dev.materione.api.gui.GuiComponent.class);
        for (var component : dev.materione.content.gui.GuiComponents.builtins()) {
            components.register(dev.materione.api.NamespacedId.of("materione", component.name()),
                    component, component.describe(), "materione");
        }
        return new dev.materione.content.gui.GuiType(components);
    }

    private static void checkTemplateStarter(String serves, String id,
            dev.materione.api.content.ContentType type) {
        String starter = Scaffolds.templateFor(serves, id);
        assertParses(starter, id);
        var declared = type.describe().parameters().stream()
                .map(dev.materione.api.registry.ParameterSchema::id).toList();
        Map<?, ?> fields = (Map<?, ?>) singleRoot(starter, id).get(id);
        assertThat(fields).as("%s template starter is not empty", serves).isNotEmpty();
        assertThat(fields.keySet().stream().map(String::valueOf).toList())
                .as("template starter for %s only sets fields %s declares", serves, type.name())
                .isSubsetOf(declared);
    }

    // ------------------------------------------------------------------

    private static void check(String dir, String id,
            dev.materione.api.content.ContentType type) {
        String scaffold = Scaffolds.forPath(dir + "/" + id + ".yml", null);
        assertParses(scaffold, id);

        var required = type.describe().parameters().stream()
                .filter(dev.materione.api.registry.ParameterSchema::required)
                .map(dev.materione.api.registry.ParameterSchema::id)
                .toList();

        Map<?, ?> root = singleRoot(scaffold, id);
        List<String> present = new java.util.ArrayList<>();
        for (Object key : ((Map<?, ?>) root.get(id)).keySet()) {
            present.add(String.valueOf(key));
        }
        assertThat(present)
                .as("scaffold for %s must cover all required parameters of %s", dir, type.name())
                .containsAll(required);

        // The declared defaults should match what the scaffold ships where it
        // pre-fills a field — checked loosely: any scaffold value for an
        // optional enum parameter with a default stays inside allowedValues.
        type.describe().parameters().stream()
                .filter(parameter -> !parameter.required()
                        && parameter.allowedValues() != null && !parameter.allowedValues().isEmpty())
                .forEach(parameter -> {
                    if (present.contains(parameter.id())) {
                        Object value = ((Map<?, ?>) root.get(id)).get(parameter.id());
                        assertThat(parameter.allowedValues())
                                .as("%s scaffold %s=%s within allowedValues",
                                        dir, parameter.id(), value)
                                .contains(String.valueOf(value).split(" ")[0]);
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> singleRoot(String scaffold, String id) {
        return LosslessYaml.parse(scaffold).data();
    }

    private static void assertParses(String scaffold, String id) {
        var document = LosslessYaml.parse(scaffold);
        assertThat(document.parseError())
                .as("scaffold %s parses cleanly", id)
                .isEmpty();
    }
}
