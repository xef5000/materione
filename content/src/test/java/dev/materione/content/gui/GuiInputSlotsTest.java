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

    @Test
    void anInputOnThePlayerRegionIsRejectedWithItsLayer() {
        Map<String, Object> player = GuiTestSupport.layer("bag", "slots", "region", "player", "input", true);
        var parsed = GuiTestSupport.parse(GuiTestSupport.type(), GuiTestSupport.definition(1, player));

        assertThat(parsed.skipped()).extracting(GuiDefinition.SkippedLayer::layerId).containsExactly("bag");
        assertThat(parsed.skipped().get(0).reason()).contains("container");
    }
}
