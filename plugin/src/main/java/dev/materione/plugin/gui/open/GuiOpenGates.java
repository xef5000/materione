package dev.materione.plugin.gui.open;

import dev.materione.plugin.commands.PlayerNames;
import dev.materione.api.NamespacedId;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.variable.StackRef;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.event.GuiContexts;
import dev.materione.plugin.logic.variable.VariableService;
import dev.materione.plugin.view.open.OpenGate;
import dev.materione.plugin.view.open.OpenSpecGate;
import java.util.Map;

/**
 * The shared {@link OpenSpecGate} configured for the GUI kind: the one seam a GUI supplies is the context its open
 * requirement and deny steps run against ({@link GuiContexts#open}). A dialog's opener builds the same gate with
 * its own context (dialogs.md §7.1).
 */
public final class GuiOpenGates {

    /** The kind's name in {@link OpenGate.Subject#kind()}. */
    public static final String KIND = "gui";

    private GuiOpenGates() {}

    public static OpenSpecGate of(
            OpenSpecGate.Permissions permissions, PlayerNames players, OpenSpecGate.DenyRunner denies) {
        return of(permissions, players, denies, null);
    }

    public static OpenSpecGate of(
            OpenSpecGate.Permissions permissions, PlayerNames players, OpenSpecGate.DenyRunner denies,
            VariableService variables) {
        return new OpenSpecGate(permissions, players, denies, variables, new OpenSpecGate.OpenContexts() {
            @Override
            public LogicContext open(
                    Viewer viewer, NamespacedId id, Map<String, ?> typedArgs, VariableService variables) {
                return GuiContexts.open(viewer, id, typedArgs, variables);
            }

            @Override
            public LogicContext open(
                    Viewer viewer, NamespacedId id, Map<String, ?> typedArgs, VariableService variables,
                    StackRef stack) {
                return GuiContexts.open(viewer, id, typedArgs, variables, stack);
            }
        });
    }
}
