package dev.materione.plugin.commands;

import dev.materione.api.NamespacedId;
import dev.materione.plugin.gui.RuntimeGuis;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Something that declares command names with typed arguments and a gate, as the command tree sees it (logic.md §40):
 * a GUI's {@code open:} block, or a {@code command} document. The registrar claims names from these and the handler
 * runs them; neither knows which kind a name came from beyond what to do when it is run.
 *
 * @param kind what the names run
 * @param id the GUI or command document
 * @param plan the compiled names, arguments and gate
 */
public record OpenDeclaration(Kind kind, NamespacedId id, OpenPlan plan) {

    /** What a declared name runs: a GUI to open (the {@code open:} sugar), or a command document's flow. */
    public enum Kind {
        GUI,
        COMMAND
    }

    public OpenDeclaration {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(plan, "plan");
    }

    /** The GUIs of a view that carry an {@code open:} block, in the view's order. */
    public static List<OpenDeclaration> of(RuntimeGuis view) {
        var out = new ArrayList<OpenDeclaration>();
        for (RuntimeGuis.Live gui : view.all()) {
            if (gui.open() != null) {
                out.add(new OpenDeclaration(Kind.GUI, gui.id(), gui.open()));
            }
        }
        return out;
    }
}
