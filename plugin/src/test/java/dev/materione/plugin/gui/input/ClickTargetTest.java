package dev.materione.plugin.gui.input;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.content.gui.PlayerInventory;
import dev.materione.content.gui.CompiledGui;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Where a click lands, from the window's policy (logic.md §6.5, §25). */
class ClickTargetTest {

    private static final InputSlots INPUTS = InputSlots.of(List.of(
            new InputSlots.Input(9, "wells", null, false),
            new InputSlots.Input(10, "wells", null, false)), false);

    private static final InputSlots LOCKED_INPUTS = InputSlots.of(List.of(
            new InputSlots.Input(9, "wells", null, false)), true);

    @Test
    void aWindowWithInputSlotsSplitsItsSlotsAndTheViewersInventory() {
        WindowPolicy policy = WindowPolicy.live(INPUTS);

        assertThat(ClickTarget.of(policy, false, true, 9)).isEqualTo(ClickTarget.INPUT_SLOT);
        assertThat(ClickTarget.of(policy, false, true, 3)).isEqualTo(ClickTarget.GUI_TOP);
        assertThat(ClickTarget.of(policy, false, false, 30)).isEqualTo(ClickTarget.VIEWER_INPUTS);
        assertThat(ClickTarget.of(policy, true, false, -1)).isEqualTo(ClickTarget.OUTSIDE_WINDOW);
    }

    @Test
    void aLockedInventoryIsLockedWhateverTheInputs() {
        assertThat(ClickTarget.of(WindowPolicy.live(LOCKED_INPUTS), false, false, 30))
                .isEqualTo(ClickTarget.VIEWER_LOCKED);
        assertThat(ClickTarget.of(WindowPolicy.live(InputSlots.of(List.of(), true)), false, false, 30))
                .as("a locked GUI with no input slots still locks its viewer").isEqualTo(ClickTarget.VIEWER_LOCKED);
    }

    @Test
    void aGuiWithNoInputSlotsKeepsTheViewerInventoryAsItWas() {
        assertThat(ClickTarget.of(WindowPolicy.live(InputSlots.NONE), false, false, 30))
                .isEqualTo(ClickTarget.VIEWER);
    }

    @Test
    void aStaleWindowRefusesTheViewerAndDeliversNothingToItsInputs() {
        assertThat(ClickTarget.of(WindowPolicy.STALE, false, true, 9)).isEqualTo(ClickTarget.GUI_TOP);
        assertThat(ClickTarget.of(WindowPolicy.STALE, false, false, 30)).isEqualTo(ClickTarget.VIEWER_LOCKED);
    }

    @Test
    void theOlderGuisKeepTheirOwnBehaviour() {
        assertThat(ClickTarget.of(WindowPolicy.LEGACY, false, true, 9)).isEqualTo(ClickTarget.GUI_TOP);
        assertThat(ClickTarget.of(WindowPolicy.LEGACY, false, false, 30)).isEqualTo(ClickTarget.VIEWER);
    }

    @Test
    void theInputSlotsOfACompiledGuiAreTheSlotsItsTopmostLayerClaims() {
        // a compiled GUI's input list is what InputSlots is built from; the accept is kept as authored
        var input = new CompiledGui.Input(9, "wells", "${slot.item == 'corpus:gem_red'}");
        var compiled = new CompiledGui(NamespacedId.of("pack", "shop"), 3, "Shop", "Shop", List.of(), null, null,
                PlayerInventory.LOCKED, List.of(input));

        assertThat(compiled.playerInventory()).isEqualTo(PlayerInventory.LOCKED);
        assertThat(compiled.inputs()).containsExactly(input);
    }
}
