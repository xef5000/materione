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
     * is not an input, a {@code player_inventory:} that is neither value, and {@code durable: required} on a layer
     * that is not an input (ignored). On an input layer {@code durable: required} is honoured by the runtime, or
     * refused where the platform cannot support it ({@code MTR-0100}, row P of #247). Each is ignored, so the GUI loads
     * and says so.
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
            if (layer.compiled() instanceof SlotsComponent.Layer wells && !wells.input()
                    && wells.durable() == SlotsComponent.Durability.REQUIRED) {
                // durable applies to input slots only. On an input layer it is no longer a load-time warning: whether
                // the server can honour it is the platform's capability check (MTR-0100, row P of #247), which the
                // content module cannot know
                out.add(new Diagnostic(
                        DiagnosticCodes.GUI_INPUT_SETTING_IGNORED, Severity.WARN,
                        gui.id() + ": layer '" + layer.id() + "' has durable, which applies to input slots "
                                + "only; it is ignored",
                        gui.id(), null, "add input: true to the layer, or remove durable",
                        DiagnosticTarget.field("layers", String.valueOf(index), "durable")));
            }
        }
    }
}
