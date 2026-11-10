package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.gui.TextureSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The input-slot settings of a GUI (logic.md §25): an input {@code slots} layer, its {@code accept:}, the
 * {@code player_inventory:} setting, and the diagnostics for the settings that cannot apply.
 */
class GuiInputSlotsTest {

    private static final NamespacedId GUI = NamespacedId.of("shop", "trade");

    private static GuiCompilation compile(Map<String, Object> definition) {
        GuiDefinition parsed = GuiTestSupport.parse(GuiTestSupport.type(), definition);
        return GuiCompiler.compile(parsed, 0xE200, TextureSource.NONE);
    }

    private static List<Diagnostic> diagnose(Map<String, Object> definition) {
        return GuiTestSupport.type().diagnose(GuiTestSupport.parse(GuiTestSupport.type(), definition));
    }

    @Test
    void anInputLayerMakesItsContainerSlotsInputsWithTheAcceptAsAuthored() {
        Map<String, Object> wells = GuiTestSupport.layer("wells", "slots",
                "region", "container", "input", true, "accept", "${slot.item == 'corpus:gem_red'}");
        var model = compile(GuiTestSupport.definition(2, wells)).model();

        assertThat(model.inputs()).hasSize(18);
        assertThat(model.inputs().get(0)).isEqualTo(
                new CompiledGui.Input(0, "wells", "${slot.item == 'corpus:gem_red'}"));
        assertThat(model.inputs()).allSatisfy(input -> assertThat(input.layer()).isEqualTo("wells"));
    }

    @Test
    void aSlotUnderAClickableLayerAboveTheInputsIsNotAnInputSlot() {
        Map<String, Object> wells = GuiTestSupport.layer("wells", "slots", "region", "container", "input", true);
        Map<String, Object> buy = GuiTestSupport.layer("buy", "button", "x", 8, "y", 18, "width", 16, "height", 16);
        var model = compile(GuiTestSupport.definition(2, wells, buy)).model();

        // slot 0 is under the button, which is topmost, so it is a button slot and not an input
        assertThat(model.inputs()).extracting(CompiledGui.Input::slot).doesNotContain(0).contains(1);
    }

    @Test
    void conditionalAndAnimatedBlockersRetainPotentialInputOwnership() {
        var wells = GuiTestSupport.layer("wells", "slots", "region", "container", "input", true);
        var conditional = GuiTestSupport.layer("tab", "button", "x", 8, "y", 18,
                "width", 16, "height", 16, "visible_if", false);
        var animated = GuiTestSupport.layer("moving", "button", "x", 26, "y", 18,
                "width", 16, "height", 16, "materione:overlay_frames",
                Map.of("every", 4, "layers", List.of(Map.of("x", 8), Map.of("x", 26))));
        var gui = GuiTestSupport.parse(GuiTestSupport.type(), GuiTestSupport.definition(1, wells, conditional, animated));
        var model = GuiCompiler.compile(gui, 0xE200, TextureSource.NONE,
                (layer, frame) -> 0xE201 + frame).model();
        assertThat(model.clicks().layersAt(0)).containsExactly("moving", "tab", "wells");
        assertThat(model.inputs()).extracting(CompiledGui.Input::slot).contains(0, 1);
        var staticBlocker = GuiTestSupport.layer("permanent", "button", "x", 8, "y", 18,
                "width", 16, "height", 16);
        gui = GuiTestSupport.parse(GuiTestSupport.type(),
                GuiTestSupport.definition(1, wells, staticBlocker, conditional));
        model = GuiCompiler.compile(gui, 0xE200, TextureSource.NONE, (layer, frame) -> 0xE201).model();
        assertThat(model.inputs()).extracting(CompiledGui.Input::slot).doesNotContain(0);
    }

    @Test
    void theViewerInventoryIsFreeUnlessItIsLocked() {
        assertThat(compile(GuiTestSupport.definition(1)).model().playerInventory()).isEqualTo(PlayerInventory.FREE);
        var locked = GuiTestSupport.definition(1);
        locked.put("player_inventory", "locked");
        assertThat(compile(locked).model().playerInventory()).isEqualTo(PlayerInventory.LOCKED);
    }

    @Test
    void aWrongPlayerInventoryValueIsDiagnosedAndFree() {
        var bad = GuiTestSupport.definition(1);
        bad.put("player_inventory", "sometimes");

        assertThat(compile(bad).model().playerInventory()).isEqualTo(PlayerInventory.FREE);
        assertThat(diagnose(bad)).extracting(Diagnostic::code).contains(DiagnosticCodes.GUI_INPUT_SETTING_IGNORED);
    }

    @Test
    void anAcceptOnALayerThatIsNotAnInputIsDiagnosedAndIgnored() {
        Map<String, Object> wells = GuiTestSupport.layer("wells", "slots", "region", "container",
                "accept", "${slot.item == 'x'}");

        assertThat(compile(GuiTestSupport.definition(1, wells)).model().inputs()).isEmpty();
        assertThat(diagnose(GuiTestSupport.definition(1, wells))).extracting(Diagnostic::code)
                .contains(DiagnosticCodes.GUI_INPUT_SETTING_IGNORED);
    }

    private static SlotsComponent.Layer wells(Map<String, Object> definition) {
        var layers = GuiTestSupport.parse(GuiTestSupport.type(), definition).layers();
        assertThat(layers).hasSize(1);
        return (SlotsComponent.Layer) layers.get(0).compiled();
    }

    @Test
    void durableDefaultsToBestEffortAndReadsBothValues() {
        assertThat(wells(GuiTestSupport.definition(1,
                GuiTestSupport.layer("w", "slots", "input", true))).durable())
                .isEqualTo(SlotsComponent.Durability.BEST_EFFORT);
        assertThat(wells(GuiTestSupport.definition(1,
                GuiTestSupport.layer("w", "slots", "input", true, "durable", "best_effort"))).durable())
                .isEqualTo(SlotsComponent.Durability.BEST_EFFORT);
        assertThat(wells(GuiTestSupport.definition(1,
                GuiTestSupport.layer("w", "slots", "input", true, "durable", "required"))).durable())
                .isEqualTo(SlotsComponent.Durability.REQUIRED);
    }

    @Test
    void anUnreadableDurableSkipsTheLayerInsteadOfGuessingBestEffort() {
        for (Object bad : new Object[] {"strict", "Required", "", true, 1}) {
            var parsed = GuiTestSupport.parse(GuiTestSupport.type(), GuiTestSupport.definition(1,
                    GuiTestSupport.layer("w", "slots", "input", true, "durable", bad)));

            assertThat(parsed.layers()).as("durable: %s", bad).isEmpty();
            assertThat(parsed.skipped()).as("durable: %s", bad).hasSize(1);
            assertThat(parsed.skipped().get(0).reason()).contains("durable must be");
        }
    }

    @Test
    void durableRequiredOnAnInputIsNoLongerALoadWarningAndTheCompiledInputCarriesIt() {
        Map<String, Object> strict = GuiTestSupport.layer("strict", "slots", "region", "container",
                "input", true, "durable", "required");
        Map<String, Object> loose = GuiTestSupport.layer("loose", "slots", "region", "container",
                "input", true, "durable", "best_effort");
        Map<String, Object> plain = GuiTestSupport.layer("plain", "slots", "region", "container", "input", true);

        // whether a server can honour required is the platform's capability check (MTR-0100), not a load warning
        var warnings = diagnose(GuiTestSupport.definition(1, strict, loose, plain)).stream()
                .filter(d -> d.code().equals(DiagnosticCodes.GUI_INPUT_SETTING_IGNORED)).toList();
        assertThat(warnings).isEmpty();
        // the compiled input says which of its slots take the escrow path: the strict layer's, and only those
        var strictModel = compile(GuiTestSupport.definition(1, strict)).model();
        assertThat(strictModel.inputs()).isNotEmpty().allMatch(CompiledGui.Input::durable);
        var looseModel = compile(GuiTestSupport.definition(1, loose)).model();
        assertThat(looseModel.inputs()).isNotEmpty().noneMatch(CompiledGui.Input::durable);
        var plainModel = compile(GuiTestSupport.definition(1, plain)).model();
        assertThat(plainModel.inputs()).isNotEmpty().noneMatch(CompiledGui.Input::durable);
    }

    @Test
    void durableRequiredOnALayerThatIsNotAnInputIsDiagnosedAndIgnored() {
        Map<String, Object> notInput = GuiTestSupport.layer("w", "slots", "region", "container", "durable", "required");
        Map<String, Object> harmless = GuiTestSupport.layer("h", "slots", "region", "container", "durable", "best_effort");

        var warnings = diagnose(GuiTestSupport.definition(1, notInput, harmless)).stream()
                .filter(d -> d.code().equals(DiagnosticCodes.GUI_INPUT_SETTING_IGNORED)).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).message()).contains("'w'").contains("applies to input slots only");
        assertThat(compile(GuiTestSupport.definition(1, notInput)).model().inputs()).isEmpty();
    }

    @Test
    void theSlotsDescriptionDeclaresDurableOnceWithTheDefaultBestEffort() {
        var durable = new SlotsComponent().describe().parameters().stream()
                .filter(p -> p.id().equals("durable")).toList();

        assertThat(durable).hasSize(1);
        assertThat(durable.get(0).allowedValues()).containsExactly("best_effort", "required");
        assertThat(durable.get(0).defaultValue()).isEqualTo("best_effort");
        assertThat(durable.get(0).required()).isFalse();
        assertThat(durable.get(0).editor().options()).extracting(o -> o.value())
                .containsExactly("best_effort", "required");
        assertThat(durable.get(0).editor().options().get(1).description()).contains("never dropped in the world").contains("Not yet verified");
    }

    @Test
    void theDurableFixtureParsesAndItsRawLayersKeepTheKeyOnlyWhereAuthored() throws Exception {
        var definition = GuiTestSupport.golden("durable-slots.yml", "vault");
        var layers = GuiTestSupport.parse(GuiTestSupport.type(), definition).layers();

        assertThat(layers).extracting(GuiLayer::id).containsExactly("plain_wells", "strict_wells", "loose_wells");
        assertThat(layers).extracting(layer -> ((SlotsComponent.Layer) layer.compiled()).durable())
                .containsExactly(SlotsComponent.Durability.BEST_EFFORT, SlotsComponent.Durability.REQUIRED,
                        SlotsComponent.Durability.BEST_EFFORT);
        @SuppressWarnings("unchecked")
        var raw = (List<Map<String, Object>>) definition.get("layers");
        assertThat(raw.get(0)).doesNotContainKey("durable");
        assertThat(raw.get(1)).containsEntry("durable", "required").containsEntry("materione:note", "kept untouched");
    }

    @Test
    void anInputOnThePlayerRegionIsRejectedWithItsLayer() {
        Map<String, Object> player = GuiTestSupport.layer("bag", "slots", "region", "player", "input", true);
        var parsed = GuiTestSupport.parse(GuiTestSupport.type(), GuiTestSupport.definition(1, player));

        assertThat(parsed.skipped()).extracting(GuiDefinition.SkippedLayer::layerId).containsExactly("bag");
        assertThat(parsed.skipped().get(0).reason()).contains("container");
    }
}
