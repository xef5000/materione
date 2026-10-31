package dev.materione.plugin.gui.dynamic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ItemResolution;
import dev.materione.api.logic.TemplateText;
import dev.materione.content.gui.CompiledGui;
import dev.materione.content.gui.ItemComponent;
import dev.materione.content.gui.item.ItemSpec;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.logic.LogicTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The per-layer compile (logic.md §6.3): problems at the authored field, and optional sources as barriers. */
class ItemLayerCompilerTest {

    private static final NamespacedId GUI = NamespacedId.of("shop", "main");

    private final ItemLayerCompiler compiler = new ItemLayerCompiler(
            LogicTestSupport.withBuiltIns(new RegistryService()).lookup());

    private static ItemComponent.Layer item(ItemSpec source, String name) {
        return new ItemComponent.Layer(4, source, null, "1", name, List.of(), ItemComponent.LoreMode.REPLACE, null);
    }

    private ItemLayerCompiler.Result compile(ItemComponent.Layer layer, Object visibleIf) {
        return compiler.compile(GUI, 3, new CompiledGui.Placement("layer", 1, 4, visibleIf, layer));
    }

    private static List<String> codes(ItemLayerCompiler.Result result) {
        return result.problems().stream().map(problem -> problem.code().code()).toList();
    }

    @Test
    void aPlainItemCompilesToItsDefaultSource() {
        var result = compile(item(new ItemSpec.Plain(NamespacedId.parse("minecraft:stone")), null), null);

        assertThat(result.layer()).isPresent();
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void anUnknownSourceLeavesTheLayerOutWithItsReason() {
        var keyed = new ItemSpec.Keyed(NamespacedId.parse("materione:nope"), Map.of("x", 1));

        var result = compile(item(keyed, null), null);

        assertThat(result.layer()).isEmpty();
        assertThat(codes(result)).containsExactly(DiagnosticCodes.GUI_ITEM_SOURCE_INVALID.code());
        assertThat(result.problems().get(0).target().field()).containsExactly("layers", "3", "item");
    }

    @Test
    void anOptionalSourceWhosePluginIsMissingIsABarrierNotAnError() {
        var keyed = new ItemSpec.Keyed(NamespacedId.parse("materione:hdb"), "12345");

        var result = compile(item(keyed, null), null);

        assertThat(result.problems()).isEmpty();
        assertThat(result.layer()).isPresent();
        ItemResolution resolved = result.layer().get().source().resolve(null);
        assertThat(resolved.failed()).isTrue();
        assertThat(resolved.reason()).contains("HeadDatabase").contains("not installed");
    }

    @Test
    void aSourceThatRejectsItsParametersLeavesTheLayerOut() {
        var head = new ItemSpec.Keyed(NamespacedId.parse("materione:head"), Map.of("player", "not a name!"));

        var result = compile(item(head, null), null);

        assertThat(result.layer()).isEmpty();
        assertThat(codes(result)).containsExactly(DiagnosticCodes.GUI_ITEM_SOURCE_INVALID.code());
    }

    @Test
    void aMalformedTemplateLeavesTheLayerOutAtTheAuthoredField() {
        var result = compile(item(new ItemSpec.Plain(NamespacedId.parse("minecraft:stone")), "Hi ${player.name"),
                null);

        assertThat(result.layer()).isEmpty();
        assertThat(codes(result)).containsExactly(DiagnosticCodes.EXPRESSION_INVALID.code());
        assertThat(result.problems().get(0).target().field()).containsExactly("layers", "3", "name");
    }

    @Test
    void anUnknownRootIsAWarningAndTheLayerStays() {
        var result = compile(item(new ItemSpec.Plain(NamespacedId.parse("minecraft:stone")), "Hi ${plyer.name}"),
                null);

        assertThat(result.layer()).isPresent();
        assertThat(codes(result)).containsExactly(DiagnosticCodes.FLOW_UNKNOWN_CONTEXT_ROOT.code());
    }

    @Test
    void aConditionThatDoesNotCompileLeavesTheLayerOutAtVisibleIf() {
        var result = compile(item(new ItemSpec.Plain(NamespacedId.parse("minecraft:stone")), null),
                "player.level >");

        assertThat(result.layer()).isEmpty();
        assertThat(result.problems()).isNotEmpty();
        assertThat(result.problems()).allMatch(problem -> problem.target().field().contains("visible_if"));
    }

    @Test
    void aGoodConditionIsCompiledAndTheLayerIsDynamic() {
        var result = compile(item(new ItemSpec.Plain(NamespacedId.parse("minecraft:stone")), null),
                "player.level > 5");

        assertThat(result.layer()).isPresent();
        assertThat(result.layer().get().visibleIf()).isNotNull();
        assertThat(result.layer().get().dynamic()).isTrue();
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void theTemplateAmountIsKeptAsATemplate() {
        var layer = new ItemComponent.Layer(4, new ItemSpec.Plain(NamespacedId.parse("minecraft:stone")), null,
                "${vars.n}", null, List.of(), ItemComponent.LoreMode.REPLACE, null);

        var result = compile(layer, null);

        TemplateText amount = result.layer().orElseThrow().amount();
        assertThat(amount.source()).isEqualTo("${vars.n}");
    }
}
