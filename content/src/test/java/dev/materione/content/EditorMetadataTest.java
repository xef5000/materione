package dev.materione.content;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.content.ContentType;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.content.blocks.BlockType;
import dev.materione.content.effects.EffectType;
import dev.materione.content.furniture.FurnitureType;
import dev.materione.content.glyphs.GlyphType;
import dev.materione.content.items.ItemType;
import dev.materione.content.paintings.PaintingType;
import dev.materione.content.recipes.RecipeType;
import dev.materione.content.sounds.SoundType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Editor presentation metadata is declarative and never weakens validation
 * (plugin.md §3): these invariants keep every type's module, option and range
 * declarations honest, so the editor can derive its modules and smart inputs
 * from the self-description alone (issue #153).
 */
class EditorMetadataTest {

    private static List<ContentType> types() {
        return List.of(new ItemType(List.of("STICK")), new BlockType(), new FurnitureType(),
                new RecipeType(), new GlyphType(), new PaintingType(), new SoundType(),
                new EffectType());
    }

    private static List<ParameterSchema> allParameters() {
        var out = new ArrayList<ParameterSchema>();
        for (ContentType type : types()) {
            out.addAll(type.describe().parameters());
        }
        return out;
    }

    @Test
    void labelledOptionsOnlyNameValuesTheTypeAccepts() {
        for (ContentType type : types()) {
            for (ParameterSchema parameter : type.describe().parameters()) {
                var options = parameter.editor().options();
                if (options.isEmpty()) {
                    continue;
                }
                if (!parameter.allowedValues().isEmpty()) {
                    assertThat(options.stream().map(ParameterSchema.EditorOption::value))
                            .as(type.name() + "." + parameter.id())
                            .containsExactlyInAnyOrderElementsOf(parameter.allowedValues());
                } else if ("presets".equals(parameter.editor().widget())
                        && parameter.type() == ParameterType.STRING) {
                    // suggestions for free text (a subtitle): any string is a fine option
                    assertThat(options).as(type.name() + "." + parameter.id()).isNotEmpty();
                } else {
                    assertThat(parameter.type())
                            .as(type.name() + "." + parameter.id() + " options without allowed values are numbers")
                            .isIn(ParameterType.INTEGER, ParameterType.DECIMAL);
                    options.forEach(option -> assertThat(Double.parseDouble(option.value())).isNotNaN());
                }
            }
        }
    }

    @Test
    void numberRangesAreOrderedAndOnlyOnNumbers() {
        for (ParameterSchema parameter : allParameters()) {
            var editor = parameter.editor();
            if (editor.min() == null && editor.max() == null && editor.unit().isBlank()) {
                continue;
            }
            assertThat(parameter.type()).as(parameter.id()).isIn(ParameterType.INTEGER, ParameterType.DECIMAL);
            if (editor.min() != null && editor.max() != null) {
                assertThat(editor.min()).as(parameter.id()).isLessThanOrEqualTo(editor.max());
            }
        }
    }

    @Test
    void everyModuleCarriesACategoryAndAnIconSoThePickerNeverGuesses() {
        for (ContentType type : types()) {
            for (ParameterSchema parameter : type.describe().parameters()) {
                var editor = parameter.editor();
                if (!editor.module()) {
                    continue;
                }
                assertThat(editor.advanced()).as(type.name() + "." + parameter.id()
                        + " is a module, so it is not hidden behind Advanced").isFalse();
                assertThat(editor.category()).as(type.name() + "." + parameter.id()).isNotBlank();
                // The mechanics source expands to one module per mechanic with its own icon.
                if (!"mechanics".equals(editor.widget())) {
                    assertThat(editor.icon()).as(type.name() + "." + parameter.id()).isNotBlank();
                }
            }
        }
    }

    @Test
    void displayFieldsAreNotModulesAndConditionsNameARealSibling() {
        for (ContentType type : types()) {
            var parameters = type.describe().parameters();
            for (ParameterSchema parameter : parameters) {
                var editor = parameter.editor();
                String where = type.name() + "." + parameter.id();
                if (editor.display()) {
                    assertThat(editor.module()).as(where + " is on the display card, not a module")
                            .isFalse();
                }
                if (editor.shownWhen().isBlank()) {
                    continue;
                }
                int eq = editor.shownWhen().indexOf('=');
                assertThat(eq).as(where + " shownWhen is field=value").isPositive();
                String siblingId = editor.shownWhen().substring(0, eq);
                var sibling = parameters.stream()
                        .filter(candidate -> candidate.id().equals(siblingId)).findFirst();
                assertThat(sibling).as(where + " names a sibling").isPresent();
                for (String value : editor.shownWhen().substring(eq + 1).split("[|]")) {
                    assertThat(sibling.get().allowedValues()).as(where).contains(value);
                }
            }
        }
    }

    @Test
    void widgetsTheEditorRendersAreNeverTypedByAnUnrenderableName() {
        // `integer` / `boolean` were once declared as widgets: the editor has
        // `number` / `toggle`, so those fields silently fell back to Advanced.
        for (ParameterSchema parameter : allParameters()) {
            assertThat(parameter.editor().widget()).as(parameter.id())
                    .isNotIn("integer", "boolean", "decimal");
        }
    }
}
