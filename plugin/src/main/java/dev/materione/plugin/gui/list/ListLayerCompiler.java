package dev.materione.plugin.gui.list;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticTarget;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.ListSource;
import dev.materione.behavior.flow.Condition;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.content.gui.CompiledGui;
import dev.materione.content.gui.list.ListSpec;
import dev.materione.plugin.gui.dynamic.CompiledItemLayer;
import dev.materione.plugin.gui.dynamic.ItemLayerCompiler;
import dev.materione.plugin.gui.dynamic.SourcePayload;
import dev.materione.plugin.gui.input.InputSlots;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Compiles one {@code list} layer for the live view (logic.md §6.7): its source (looked up and parsed), its filter
 * and its entry template, with every problem reported at the field it is about ({@code layers.<index>.<field>}).
 * Pure: it reads the registries through {@link LogicLookup} and touches no server, so the diagnose stage and the
 * live view compile the same way.
 *
 * <p>A list that cannot be compiled is left out ({@code MTR-0081} when its source is unknown or rejects its
 * parameters), and the rest of the GUI still shows. The entry template is an item layer compiled with the entry
 * roots, so every rule about item sources and overrides applies to it unchanged.
 */
public final class ListLayerCompiler {

    /** The outcome of compiling one list.
     *
     * @param layer the compiled list, or empty when it is left out
     * @param problems what was found, each targeted at the authored field
     */
    public record Result(Optional<CompiledListLayer> layer, List<Diagnostic> problems) {

        public Result {
            problems = List.copyOf(problems);
        }
    }

    private final LogicLookup lookup;
    private final ItemLayerCompiler entries;

    /** @param lookup the registries sources, conditions and item sources resolve against */
    public ListLayerCompiler(LogicLookup lookup) {
        this.lookup = lookup;
        this.entries = new ItemLayerCompiler(lookup, ItemLayerCompiler.ENTRY_ROOTS);
    }

    /**
     * The GUI's list layers, compiled against the registries for a live view (logic.md §6.7). A list whose source
     * does not compile is left out; its problem was reported by the diagnose stage. A list slot that is an input slot
     * is reported ({@code MTR-0084}): the player's items own it, so its entry is not shown there.
     */
    public static List<CompiledListLayer> compileAll(
            CompiledGui gui, LogicLookup logic, InputSlots inputs, Consumer<Diagnostic> diagnostics) {
        if (gui.lists().isEmpty()) {
            return List.of();
        }
        var compiler = new ListLayerCompiler(logic);
        var out = new ArrayList<CompiledListLayer>(gui.lists().size());
        for (CompiledGui.ListPlacement placement : gui.lists()) {
            for (int slot : placement.list().slots()) {
                if (inputs.isInput(slot)) {
                    diagnostics.accept(listSlotIsInput(gui, placement, slot));
                }
            }
            compiler.compile(gui.id(), placement.order(), placement).layer().ifPresent(out::add);
        }
        return List.copyOf(out);
    }

    private static Diagnostic listSlotIsInput(CompiledGui gui, CompiledGui.ListPlacement placement, int slot) {
        return new Diagnostic(
                DiagnosticCodes.GUI_LIST_SLOT_INPUT, Severity.WARN,
                "GUI " + gui.id() + " list '" + placement.layerId() + "' fills slot " + slot
                        + ", which is an input slot; its entry is not shown there",
                gui.id(), null,
                "move the list's slots off the input layer's slots, or make the layer not an input",
                null);
    }

    /**
     * Compiles one list.
     *
     * @param gui the GUI's id (the diagnostics' resource)
     * @param authoredIndex the layer's position in the authored {@code layers:} list, for the target
     * @param placement the parsed list layer
     */
    public Result compile(NamespacedId gui, int authoredIndex, CompiledGui.ListPlacement placement) {
        var problems = new ArrayList<Diagnostic>();
        try {
            return new Result(Optional.of(build(gui, authoredIndex, placement, problems)), problems);
        } catch (Rejected rejected) {
            return new Result(Optional.empty(), problems);
        }
    }

    private CompiledListLayer build(
            NamespacedId gui, int index, CompiledGui.ListPlacement placement, List<Diagnostic> problems) {
        var list = placement.list();
        ListSpec spec = list.source();
        ListSource<?> source = lookup.listSource(spec.source().toString()).orElseThrow(() -> {
            problems.add(invalid(gui, index, "source", "list source '" + spec.source() + "' is not known; the list is left out",
                    "check the spelling, or install the addon that provides the source"));
            return new Rejected();
        });
        ListBinding binding;
        try {
            binding = ListBinding.bind(source, SourcePayload.params(source.describe().shorthand(), spec.payload(),
                    EngineExpressions.with(lookup.functions())));
        } catch (IllegalArgumentException rejected) {
            problems.add(invalid(gui, index, "source", "list source '" + spec.source() + "' rejected: "
                    + rejected.getMessage(), "fix the source's parameters; the list is left out"));
            throw new Rejected();
        }
        Condition filter = null;
        if (list.filter() != null) {
            var compiled = FlowCompiler.compileCondition(
                    gui, "list " + placement.layerId(), list.filter(), ItemLayerCompiler.ENTRY_ROOTS, lookup);
            for (Diagnostic problem : compiled.problems()) {
                problems.add(retarget(problem, gui, index, "filter"));
            }
            filter = compiled.condition().orElseThrow(Rejected::new);
        }
        var entry = entries.compile(gui, index, new CompiledGui.Placement(
                placement.layerId(), placement.order(), list.slots().get(0), null, list.entry()));
        problems.addAll(entry.problems());
        CompiledItemLayer template = entry.layer().orElseThrow(Rejected::new);
        return new CompiledListLayer(placement.layerId(), placement.order(), list.slots(), binding,
                source.describe().cached(), filter, list.sort(), template, placement);
    }

    private static Diagnostic invalid(NamespacedId gui, int index, String field, String message, String hint) {
        return new Diagnostic(DiagnosticCodes.GUI_LIST_SOURCE_INVALID, Severity.WARN,
                "GUI " + gui + " layer list " + field + ": " + message, gui, null, hint, target(index, field));
    }

    private static Diagnostic retarget(Diagnostic problem, NamespacedId gui, int index, String field) {
        return new Diagnostic(problem.code(), problem.severity(), problem.message(), gui, null, problem.hint(),
                target(index, field));
    }

    private static DiagnosticTarget target(int index, String field) {
        return DiagnosticTarget.field("layers", String.valueOf(index), field);
    }

    /** Stops compiling one list; its problems are already in the list. */
    private static final class Rejected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Rejected() {
            super(null, null, false, false);
        }
    }
}
