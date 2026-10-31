package dev.materione.plugin.gui.dynamic;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticTarget;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.ExpressionCompiler;
import dev.materione.api.logic.ItemSource;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.TemplateText;
import dev.materione.behavior.expr.ExprRoots;
import dev.materione.behavior.flow.Condition;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.interp.Template;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.content.gui.CompiledGui;
import dev.materione.content.gui.ItemComponent;
import dev.materione.content.gui.item.ItemSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Compiles one item layer of a GUI for the live view (logic.md §6.3): its {@code visible_if} condition, its
 * item source (and frame sources), and its templated overrides, with every problem reported at the field it
 * is about ({@code layers.<index>.item}). Pure: it reads the registries through {@link LogicLookup} and
 * touches no server, so the diagnose stage and the live view compile the same way.
 *
 * <p>A layer that cannot be compiled is left out, and the rest of the GUI still shows. A source of an
 * optional integration that is not installed is not such a case: the layer compiles to a barrier that names
 * the plugin (logic.md §4.4), so an author sees the missing dependency in the window.
 */
public final class ItemLayerCompiler {

    /** The variable roots an item layer may read: the viewer, the server, run variables and the GUI itself. */
    public static final Set<String> ROOTS = Set.of("player", "server", "vars", "gui");

    /** Source names of the optional integrations, by the plugin each one needs. */
    static final Map<String, String> OPTIONAL_SOURCES = Map.of(
            "hdb", "HeadDatabase",
            "itemsadder", "ItemsAdder",
            "nexo", "Nexo",
            "mmoitems", "MMOItems");

    /**
     * The outcome of compiling one layer.
     *
     * @param layer the compiled layer, or empty when it is left out
     * @param problems what was found, each targeted at the authored field
     */
    public record Result(Optional<CompiledItemLayer> layer, List<Diagnostic> problems) {

        public Result {
            problems = List.copyOf(problems);
        }
    }

    private final LogicLookup lookup;
    private final ExpressionCompiler expressions;

    /** @param lookup the registries conditions, functions and item sources resolve against */
    public ItemLayerCompiler(LogicLookup lookup) {
        this.lookup = lookup;
        this.expressions = EngineExpressions.with(lookup.functions());
    }

    /**
     * Compiles one placement.
     *
     * @param gui the GUI's id (the diagnostics' resource)
     * @param authoredIndex the layer's position in the authored {@code layers:} list, for the target
     * @param placement the parsed layer
     */
    public Result compile(NamespacedId gui, int authoredIndex, CompiledGui.Placement placement) {
        var problems = new ArrayList<Diagnostic>();
        try {
            return new Result(Optional.of(build(gui, authoredIndex, placement, problems)), problems);
        } catch (Rejected rejected) {
            return new Result(Optional.empty(), problems);
        }
    }

    private CompiledItemLayer build(
            NamespacedId gui, int index, CompiledGui.Placement placement, List<Diagnostic> problems) {
        Condition condition = null;
        if (placement.visibleIf() != null) {
            var compiled = FlowCompiler.compileCondition(
                    gui, "layer " + placement.layerId(), placement.visibleIf(), ROOTS, lookup);
            for (Diagnostic problem : compiled.problems()) {
                problems.add(retarget(problem, gui, index, "visible_if"));
            }
            condition = compiled.condition().orElseThrow(Rejected::new);
        }
        ItemComponent.Layer item = placement.item();
        ItemBinding source = null;
        List<ItemBinding> frames = null;
        int every = 0;
        if (item.frames() != null) {
            frames = new ArrayList<>();
            for (ItemSpec spec : item.frames().items()) {
                frames.add(binding(gui, index, "frames", spec, problems));
            }
            every = item.frames().every();
        } else {
            source = binding(gui, index, "item", item.item(), problems);
        }
        TemplateText name = item.name() == null ? null : template(gui, index, "name", item.name(), problems);
        var lore = new ArrayList<TemplateText>(item.lore().size());
        for (String line : item.lore()) {
            lore.add(template(gui, index, "lore", line, problems));
        }
        TemplateText amount = template(gui, index, "amount", item.amount(), problems);
        TemplateText glint = item.glint() == null ? null : template(gui, index, "glint", item.glint(), problems);
        return new CompiledItemLayer(placement.layerId(), placement.order(), placement.slot(), condition, source,
                frames, every, name, lore, item.loreMode(), amount, glint, placement.dynamic());
    }

    /** The source of one authored item: a plain id or template goes to the default source, a named one to its own. */
    private ItemBinding binding(NamespacedId gui, int index, String field, ItemSpec spec, List<Diagnostic> problems) {
        return switch (spec) {
            case ItemSpec.Plain plain ->
                    sourceBinding(gui, index, field, ItemSpec.DEFAULT_SOURCE, Map.of("item", plain.id().toString()),
                            problems);
            case ItemSpec.Text text ->
                    sourceBinding(gui, index, field, ItemSpec.DEFAULT_SOURCE, Map.of("item", text.template()),
                            problems);
            case ItemSpec.Keyed keyed ->
                    sourceBinding(gui, index, field, keyed.source(), keyed.payload(), problems);
        };
    }

    private ItemBinding sourceBinding(
            NamespacedId gui, int index, String field, NamespacedId sourceId, Object payload,
            List<Diagnostic> problems) {
        Optional<ItemSource<?>> found = lookup.itemSource(sourceId.toString());
        if (found.isEmpty()) {
            String optional = optionalPlugin(sourceId);
            if (optional != null) {
                return ItemBinding.unavailable("the " + sourceId.value() + ": item source needs " + optional
                        + ", which is not installed on this server");
            }
            problems.add(invalid(gui, index, field,
                    "item source '" + sourceId + "' is not known; the layer is left out",
                    "check the spelling, or install the addon that provides the source"));
            throw new Rejected();
        }
        try {
            return ItemBinding.bind(found.get(), paramsOf(found.get(), payload));
        } catch (IllegalArgumentException rejected) {
            problems.add(invalid(gui, index, field, "item source '" + sourceId + "' rejected: "
                    + rejected.getMessage(), "fix the source's parameters; the layer is left out"));
            throw new Rejected();
        }
    }

    /** A scalar payload is the source's shorthand: {@code player_slot: main_hand} reads as {@code {slot: main_hand}}. */
    private ParamReader paramsOf(ItemSource<?> source, Object payload) {
        if (payload instanceof Map<?, ?> mapping) {
            var params = new java.util.LinkedHashMap<String, Object>();
            mapping.forEach((key, value) -> {
                if (!(key instanceof String name)) {
                    throw new IllegalArgumentException("parameter names are text");
                }
                params.put(name, value);
            });
            return ParamReader.of(params, expressions);
        }
        String shorthand = source.describe().shorthand();
        if (shorthand == null) {
            throw new IllegalArgumentException("takes a mapping of parameters, not a single value");
        }
        // a number or a boolean as written (hdb: 12345) is its text, which is how a source reads it
        Object text = payload instanceof String ? payload : String.valueOf(payload);
        return ParamReader.of(Map.of(shorthand, text), expressions);
    }

    /** The plugin an optional source needs, when {@code id} is one of them and the registry lacks it. */
    private static String optionalPlugin(NamespacedId id) {
        return NamespacedId.of("materione", id.value()).equals(id) ? OPTIONAL_SOURCES.get(id.value()) : null;
    }

    private TemplateText template(NamespacedId gui, int index, String field, String text, List<Diagnostic> problems) {
        TemplateText compiled;
        try {
            if (unterminated(text)) {
                // the engine keeps an unclosed ${ as literal text; in a GUI that is a typo the author must see
                throw new IllegalArgumentException("'${' is never closed by '}' in '" + text + "'");
            }
            compiled = expressions.template(text);
        } catch (IllegalArgumentException malformed) {
            problems.add(new Diagnostic(DiagnosticCodes.EXPRESSION_INVALID, Severity.WARN,
                    "GUI " + gui + " layer item " + field + " has a malformed expression: " + malformed.getMessage(),
                    gui, null, "fix the ${...} segment; the layer is left out",
                    target(index, field)));
            throw new Rejected();
        }
        checkRoots(gui, index, field, text, problems);
        return compiled;
    }

    /** True when some {@code ${} has no {@code }} after it. */
    static boolean unterminated(String text) {
        int open = text.indexOf("${");
        while (open >= 0) {
            if (text.indexOf('}', open + 2) < 0) {
                return true;
            }
            open = text.indexOf("${", open + 2);
        }
        return false;
    }

    /** An unknown root in a template is a warning: the value reads as empty, and the rest of the layer stands. */
    private void checkRoots(NamespacedId gui, int index, String field, String text, List<Diagnostic> problems) {
        if (!text.contains("${")) {
            return;
        }
        var found = new LinkedHashSet<String>();
        for (var expression : Template.compile(text, lookup.functions()).expressions()) {
            ExprRoots.collect(expression.tree(), found);
        }
        for (String root : found) {
            if (!ROOTS.contains(root)) {
                problems.add(new Diagnostic(DiagnosticCodes.FLOW_UNKNOWN_CONTEXT_ROOT, Severity.WARN,
                        "GUI " + gui + " layer item " + field + " reads '" + root + "', which is not a variable of a GUI layer",
                        gui, null, "known roots: " + String.join(", ", new java.util.TreeSet<>(ROOTS))
                                + "; the value reads as empty until fixed",
                        target(index, field)));
            }
        }
    }

    private static Diagnostic invalid(NamespacedId gui, int index, String field, String message, String hint) {
        return new Diagnostic(DiagnosticCodes.GUI_ITEM_SOURCE_INVALID, Severity.WARN,
                "GUI " + gui + " layer item " + field + ": " + message, gui, null, hint, target(index, field));
    }

    private static Diagnostic retarget(Diagnostic problem, NamespacedId gui, int index, String field) {
        return new Diagnostic(problem.code(), problem.severity(), problem.message(), gui, null, problem.hint(),
                target(index, field));
    }

    private static DiagnosticTarget target(int index, String field) {
        return DiagnosticTarget.field("layers", String.valueOf(index), field);
    }

    /** Stops compiling one layer; its problems are already in the list. */
    private static final class Rejected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Rejected() {
            super(null, null, false, false);
        }
    }
}
