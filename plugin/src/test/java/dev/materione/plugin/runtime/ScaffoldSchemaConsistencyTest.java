package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.content.blocks.BlockType;
import dev.materione.content.furniture.FurnitureType;
import dev.materione.content.items.ItemType;
import dev.materione.content.recipes.RecipeType;
import dev.materione.core.document.LosslessYaml;
import dev.materione.web.rpc.ops.Scaffolds;
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
        // Templates have no registry type — parse-cleanliness only.
        assertParses(Scaffolds.forPath("templates/base_pack.yml", null), "base_pack");
    }

    // ------------------------------------------------------------------

    private static void check(String dir, String id,
            dev.materione.core.content.ContentType type) {
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
