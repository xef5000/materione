package dev.materione.content.gui;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticTarget;
import dev.materione.api.diagnostics.Severity;
import java.util.List;

/** The input-slot setting diagnostics of a GUI ({@code MTR-0076}), split from {@link GuiType} (logic.md §25). */
final class GuiInputDiagnostics {

    private GuiInputDiagnostics() {
    }

    /**
     * The input-slot settings that cannot apply (logic.md §25, MTR-0076): {@code accept:} on a layer that
     * is not an input, a {@code player_inventory:} that is neither value, and {@code durable: required}
     * (ignored on a non-input; on an input it is schema only until row P of #247, so it is stated to behave as
     * {@code best_effort}). Each is ignored, so the GUI loads and says so.
     */
    static void diagnose(GuiDefinition gui, List<Diagnostic> out) {
        if (!PlayerInventory.valid(gui.raw().get("player_inventory"))) {
            out.add(new Diagnostic(
                    DiagnosticCodes.GUI_INPUT_SETTING_IGNORED, Severity.WARN,
                    gui.id() + ": player_inventory must be locked or free; free is used",
                    gui.id(), null, "write player_inventory: locked or player_inventory: free",
                    DiagnosticTarget.field("player_inventory")));
        }
        for (int index = 0; index < gui.layers().size(); index++) {
            GuiLayer layer = gui.layers().get(index);
            if (layer.compiled() instanceof SlotsComponent.Layer wells && !wells.input() && wells.accept() != null) {
                out.add(new Diagnostic(
                        DiagnosticCodes.GUI_INPUT_SETTING_IGNORED, Severity.WARN,
                        gui.id() + ": layer '" + layer.id() + "' has accept, which applies to input slots only; it is ignored",
                        gui.id(), null, "add input: true to the layer, or remove accept",
                        DiagnosticTarget.field("layers", String.valueOf(index))));
            }
            if (layer.compiled() instanceof SlotsComponent.Layer wells
                    && wells.durable() == SlotsComponent.Durability.REQUIRED) {
                // durable is schema only until row P of #247: a required layer is not crash-safe yet, and the
                // validation result says so rather than let the word be read as a guarantee
                boolean input = wells.input();
                out.add(new Diagnostic(
                        DiagnosticCodes.GUI_INPUT_SETTING_IGNORED, Severity.WARN,
                        input
                                ? gui.id() + ": layer '" + layer.id() + "' sets durable: required, but crash-safe input "
                                        + "slots are not available in this build; it behaves as best_effort and its "
                                        + "items are not crash-safe"
                                : gui.id() + ": layer '" + layer.id() + "' has durable, which applies to input slots "
                                        + "only; it is ignored",
                        gui.id(), null,
                        input ? "keep durable: required to ask for crash-safe slots once this build supports them, "
                                + "or write durable: best_effort"
                                : "add input: true to the layer, or remove durable",
                        DiagnosticTarget.field("layers", String.valueOf(index), "durable")));
            }
        }
    }
}
