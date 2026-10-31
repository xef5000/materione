package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.gui.TextureSource;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.content.gui.item.ItemSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The item layer of logic.md §6.3 as authored: sources, templated overrides, animation, {@code visible_if} and the
 * refresh clock. Each rejection names its reason, since a skipped layer is only useful with one.
 */
class ItemLayerTest {

    private static final GuiType TYPE = GuiTestSupport.type();

    private static Map<String, Object> item(String id, Object... keysAndValues) {
        return GuiTestSupport.layer(id, "item", keysAndValues);
    }

    @SafeVarargs
    private static GuiDefinition gui(Map<String, Object>... layers) {
        var authored = new java.util.ArrayList<Object>();
        for (Map<String, Object> layer : layers) {
            authored.add(layer);
        }
        var definition = new java.util.LinkedHashMap<String, Object>();
        definition.put("title", "Test");
        definition.put("rows", 3);
        definition.put("layers", authored);
        return GuiTestSupport.parse(TYPE, definition);
    }

    private static GuiCompilation compile(GuiDefinition definition) {
        return GuiCompiler.compile(definition, 0xE200, TextureSource.NONE);
    }

    /** The reason a skipped layer was left out, or null when the layer was accepted. */
    private static String skipReason(GuiDefinition definition, String layerId) {
        return definition.skipped().stream()
                .filter(skip -> layerId.equals(skip.layerId()))
                .map(GuiDefinition.SkippedLayer::reason)
                .findFirst()
                .orElse(null);
    }

    @Test
    void aPlainIdWithNoOverrideIsAStaticSlotEntry() {
        var model = compile(gui(item("sword", "slot", 13, "item", "shop:gui/main/sword"))).model();

        assertThat(model.slots()).containsExactly(new CompiledGui.Slot(13, NamespacedId.parse("shop:gui/main/sword"), 1));
        assertThat(model.placements()).isEmpty();
    }

    @Test
    void aConditionalItemIsAPlacementAndTheTopmostVisibleLayerOfASlotWins() {
        var model = compile(gui(
                item("plain", "slot", 13, "item", "minecraft:stone"),
                item("vip", "slot", 13, "item", "minecraft:diamond", "visible_if", "player.level > 5")))
                .model();

        // both layers fill slot 13, so neither is skipped: they compete at render time, topmost first
        assertThat(model.slots()).isEmpty();
        assertThat(model.placements()).extracting(CompiledGui.Placement::layerId).containsExactly("plain", "vip");
        assertThat(model.placements().get(1).visibleIf()).isEqualTo("player.level > 5");
        assertThat(model.placements().get(1).order()).isEqualTo(1);
    }

    @Test
    void twoStaticItemsInOneSlotStillSkipTheEarlierOne() {
        var compilation = compile(gui(
                item("first", "slot", 13, "item", "minecraft:stone"),
                item("second", "slot", 13, "item", "minecraft:dirt")));

        assertThat(compilation.model().slots()).containsExactly(
                new CompiledGui.Slot(13, NamespacedId.parse("minecraft:dirt"), 1));
        assertThat(compilation.diagnostics()).extracting(d -> d.message())
                .anyMatch(message -> message.contains("filled by the later layer 'second'"));
    }

    @Test
    void itemSourcesAreParsedAndDynamicOnlyWhenTheyDependOnTheViewerOrATemplate() {
        var model = compile(gui(
                item("fixed_head", "slot", 0, "item", Map.of("head", Map.of("player", "Notch"))),
                item("viewer_head", "slot", 1, "item", Map.of("head", Map.of("player", "${player.name}"))),
                item("hand", "slot", 2, "item", Map.of("player_slot", "main_hand")),
                item("templated", "slot", 3, "item", "${entry.item}"),
                item("animated", "slot", 4, "frames", Map.of("every", 10, "items", List.of("minecraft:stone", "minecraft:dirt"))),
                item("named", "slot", 5, "item", "minecraft:stone", "name", "Hi ${player.name}"),
                item("counted", "slot", 6, "item", "minecraft:stone", "amount", "${vars.n}"),
                item("plain_name", "slot", 7, "item", "minecraft:stone", "name", "Plain")))
                .model();

        var dynamic = model.placements().stream()
                .collect(java.util.stream.Collectors.toMap(CompiledGui.Placement::layerId, CompiledGui.Placement::dynamic));
        assertThat(dynamic).containsEntry("fixed_head", false)
                .containsEntry("viewer_head", true)
                .containsEntry("hand", true)
                .containsEntry("templated", true)
                .containsEntry("animated", true)
                .containsEntry("named", true)
                .containsEntry("counted", true)
                .containsEntry("plain_name", false);
    }

    @Test
    void theItemSpecsParseTheThreeAuthoredShapesAndRejectTheRest() {
        assertThat(ItemSpec.parse("shop:gui/sword")).isEqualTo(new ItemSpec.Plain(NamespacedId.parse("shop:gui/sword")));
        assertThat(ItemSpec.parse("minecraft:diamond")).isEqualTo(new ItemSpec.Plain(NamespacedId.parse("minecraft:diamond")));
        assertThat(ItemSpec.parse("${entry.item}")).isEqualTo(new ItemSpec.Text("${entry.item}"));
        assertThat(ItemSpec.parse(Map.of("head", Map.of("player", "Notch"))))
                .isEqualTo(new ItemSpec.Keyed(NamespacedId.parse("materione:head"), Map.of("player", "Notch")));
        assertThat(ItemSpec.parse(Map.of("player_slot", "main_hand")).dynamic()).isTrue();

        assertThatRejects("  ");
        assertThatRejects("Not An Id!");
        assertThatRejects(Map.of("head", "a", "player_slot", "b"));
        assertThatRejects(42);
    }

    private static void assertThatRejects(Object raw) {
        try {
            ItemSpec.parse(raw);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected " + raw + " to be rejected");
    }

    @Test
    void framesParseTheirRateAndSourcesAndRejectAnEmptyOrZeroRate() {
        var layer = (ItemComponent.Layer) gui(item("anim", "slot", 4,
                "frames", Map.of("every", 10, "items", List.of("minecraft:stone", "minecraft:dirt"))))
                .layers().get(0).compiled();

        assertThat(layer.frames().every()).isEqualTo(10);
        assertThat(layer.frames().items()).hasSize(2);
        assertThat(layer.frames().dynamic()).isTrue();
        var zero = gui(item("zero", "slot", 4, "frames", Map.of("every", 0, "items", List.of("minecraft:stone"))));
        assertThat(skipReason(zero, "zero")).contains("frames.every");
        var none = gui(item("none", "slot", 4, "frames", Map.of("every", 5, "items", List.of())));
        assertThat(skipReason(none, "none")).contains("at least one");
    }

    @Test
    void anItemLayerNeedsExactlyOneOfItemOrFrames() {
        assertThat(skipReason(gui(item("both", "slot", 4, "item", "minecraft:stone",
                "frames", Map.of("every", 5, "items", List.of("minecraft:dirt")))), "both"))
                .contains("exactly one");
        assertThat(skipReason(gui(item("neither", "slot", 4)), "neither")).contains("exactly one");
    }

    @Test
    void theOverridesAreTypedAtLoadAndTheirMistakesSkipTheLayer() {
        assertThat(skipReason(gui(item("big", "slot", 4, "item", "minecraft:stone", "amount", 65)), "big"))
                .contains("amount");
        assertThat(skipReason(gui(item("zero_glint", "slot", 4, "item", "minecraft:stone", "glint", "maybe")),
                "zero_glint")).contains("glint");
        assertThat(skipReason(gui(item("append", "slot", 4, "item", "minecraft:stone", "lore_mode", "append")),
                "append")).contains("lore_mode needs lore");
        assertThat(skipReason(gui(item("mode", "slot", 4, "item", "minecraft:stone", "lore", List.of("a"),
                "lore_mode", "sideways")), "mode")).contains("replace, append or prepend");
        assertThat(skipReason(gui(item("compound", "slot", 4, "item", Map.of("a", 1, "b", 2))), "compound"))
                .contains("takes an id");
        assertThat(skipReason(gui(item("bad_slot", "slot", 4, "item", "minecraft:stone", "amount", "${vars.n}",
                "glint", "${vars.g}", "lore", "one")), "bad_slot")).isNull();
    }

    @Test
    void visibleIfIsAcceptedOnItemLayersAndRejectedOtherwiseWithItsReason() {
        var definition = gui(
                item("shown", "slot", 4, "item", "minecraft:stone", "visible_if", List.of("player.level > 1")),
                Map.of("id", "label", "type", "text", "x", 8, "y", 6, "text", "Hi",
                        "visible_if", "player.level > 1"),
                item("shape", "slot", 5, "item", "minecraft:stone", "visible_if", 3));

        assertThat(skipReason(definition, "shown")).isNull();
        assertThat(skipReason(definition, "label")).contains("visible_if works on item layers");
        assertThat(skipReason(definition, "shape")).contains("visible_if is a condition");
    }

    @Test
    void theRefreshClockIsReadWhenWholeAndReportedWhenNot() {
        var definition = gui(item("sword", "slot", 4, "item", "minecraft:stone"));
        assertThat(definition.refresh()).isZero();

        var clocked = GuiTestSupport.parse(TYPE, withRefresh(20));
        assertThat(clocked.refresh()).isEqualTo(20);
        assertThat(clocked.refreshProblem()).isNull();
        assertThat(TYPE.diagnose(clocked)).extracting(d -> d.code().code()).doesNotContain("MTR-0071");

        var broken = GuiTestSupport.parse(TYPE, withRefresh("soon"));
        assertThat(broken.refresh()).isZero();
        assertThat(broken.refreshProblem()).contains("whole number of ticks");
        List<Diagnostic> found = TYPE.diagnose(broken);
        assertThat(found).extracting(d -> d.code().code()).contains("MTR-0071");
    }

    private static Map<String, Object> withRefresh(Object refresh) {
        var definition = new java.util.LinkedHashMap<>(GuiTestSupport.definition(3,
                item("sword", "slot", 4, "item", "minecraft:stone")));
        definition.put("refresh", refresh);
        return definition;
    }
}
