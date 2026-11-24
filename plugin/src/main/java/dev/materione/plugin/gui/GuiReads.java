package dev.materione.plugin.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.ListSource;
import dev.materione.api.logic.ListSourceDescription;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.content.gui.CompiledGui;
import dev.materione.content.gui.ItemComponent;
import dev.materione.content.gui.item.ItemSpec;
import dev.materione.content.gui.list.ListSpec;
import dev.materione.plugin.gui.dynamic.CompiledItemLayer;
import dev.materione.plugin.gui.dynamic.TitleProgram;
import dev.materione.plugin.gui.list.CompiledListLayer;
import dev.materione.plugin.gui.session.GuiViewerState;
import dev.materione.plugin.view.ReadRole;
import dev.materione.plugin.view.ReadsCollector;
import dev.materione.plugin.view.ReadsIndex;
import dev.materione.plugin.view.ViewReads;
import dev.materione.plugin.view.ViewRef;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a container GUI reads (views.md section 5.2): its layers' conditions and templates, the parameters of its item
 * and list sources, and its title pieces, all as compiled for the live view. Every read of a GUI is a content read:
 * a GUI has no visibility condition of its own, a viewer opens it.
 *
 * <p>The compiled item and list bindings are opaque, so a source's own variable parameters are read from its authored
 * payload: any {@code ${...}} in it, the parameters its self-description draws with the {@code variable} widget, and a
 * list's {@code search.var}.
 */
public final class GuiReads {

    /** Why a GUI reads what it reads: every read is content. */
    private static final ReadRole ROLE = ReadRole.CONTENT;

    /** The widget a source parameter is drawn with when its value names a variable. */
    private static final String VARIABLE_WIDGET = "variable";
    /** The reference kind of such a parameter whose value is the key of a session variable of the GUI's own pack. */
    private static final String SESSION_KIND = "session";

    private GuiReads() {}

    /** The reads of one live GUI, and the problems the extraction found. */
    public record Extraction(ViewReads reads, List<Diagnostic> problems) {}

    /** The reads of every live GUI of a shadow, by view: the input of the {@link ReadsIndex}. */
    public static Map<ViewRef, ViewReads> of(RuntimeGuis guis, LogicLookup lookup) {
        return of(guis, lookup, id -> true);
    }

    /**
     * As {@link #of(RuntimeGuis, LogicLookup)}, for the GUIs a write re-renders: a GUI that says {@code rerender: off}
     * (views.md section 5.5) is left out of the index, so no write marks its viewers.
     *
     * @param rerenders whether the GUI of this id re-renders on a write
     */
    public static Map<ViewRef, ViewReads> of(
            RuntimeGuis guis, LogicLookup lookup, java.util.function.Predicate<NamespacedId> rerenders) {
        var out = new LinkedHashMap<ViewRef, ViewReads>();
        for (RuntimeGuis.Live gui : guis.all()) {
            if (!rerenders.test(gui.id())) {
                continue;
            }
            out.put(new ViewRef(GuiViewerState.KIND, gui.id()), of(gui, lookup).reads());
        }
        return out;
    }

    /** Extracts one live GUI. */
    public static Extraction of(RuntimeGuis.Live gui, LogicLookup lookup) {
        var collector = new ReadsCollector(gui.id(), lookup);
        Map<String, ItemComponent.Layer> authored = new HashMap<>();
        for (CompiledGui.Placement placement : gui.placements()) {
            authored.put(placement.layerId(), placement.item());
        }
        for (CompiledItemLayer layer : gui.items()) {
            layer(collector, layer, authored.get(layer.layerId()));
        }
        for (CompiledListLayer list : gui.lists()) {
            list(collector, gui.id(), list);
        }
        visuals(collector, gui.visuals());
        return new Extraction(collector.result(), collector.problems());
    }

    /** Reads one item layer: its condition, its templates, and the parameters of its item sources. */
    public static void layer(ReadsCollector collector, CompiledItemLayer layer, ItemComponent.Layer authored) {
        collector.condition(layer.visibleIf(), ROLE);
        collector.template(layer.name() == null ? null : layer.name().source(), ROLE);
        layer.lore().forEach(line -> collector.template(line.source(), ROLE));
        collector.template(layer.amount() == null ? null : layer.amount().source(), ROLE);
        collector.template(layer.glint() == null ? null : layer.glint().source(), ROLE);
        if (authored != null) {
            if (authored.item() != null) {
                spec(collector, authored.item());
            }
            if (authored.frames() != null) {
                authored.frames().items().forEach(frame -> spec(collector, frame));
            }
        }
    }

    /** Reads one list layer: its source, filter and search, and the entry template every entry is shown with. */
    public static void list(ReadsCollector collector, NamespacedId gui, CompiledListLayer list) {
        var authored = list.authored().list();
        String pack = gui.namespace();
        collector.condition(list.filter(), ROLE);
        payload(collector, authored.source().payload());
        source(collector, authored.source(), pack);
        if (list.search() != null) {
            collector.sessionVariable(list.search().var(), pack, ROLE);
        }
        layer(collector, list.entry(), authored.entry());
    }

    /**
     * Reads what a list source reads from its self-description: a parameter drawn with the {@code variable} widget
     * names a variable, which is a notified read, a bare name being a variable of the GUI's pack; with the reference
     * kind {@code session} it names a session variable by its key. A source that does not say it reads only such
     * variables, or one that is not registered, marks the view polled.
     */
    private static void source(ReadsCollector collector, ListSpec spec, String pack) {
        ListSource<?> source = collector.listSource(spec.source().toString()).orElse(null);
        if (source == null) {
            collector.polled();
            return;
        }
        ListSourceDescription description = source.describe();
        if (!description.announced()) {
            collector.polled();
        }
        Map<?, ?> params = spec.payload() instanceof Map<?, ?> map ? map
                : description.shorthand() == null || spec.payload() == null ? Map.of()
                : Map.of(description.shorthand(), spec.payload());
        for (ParameterSchema parameter : description.self().parameters()) {
            if (!VARIABLE_WIDGET.equals(parameter.editor().widget())
                    || !(params.get(parameter.id()) instanceof String named) || named.isBlank()) {
                continue;
            }
            if (SESSION_KIND.equals(parameter.editor().referenceKind())) {
                collector.sessionVariable(named, pack, ROLE);
                continue;
            }
            try {
                collector.declaredVariable(variable(named, pack), ROLE);
            } catch (IllegalArgumentException malformed) {
                // the source rejected the name when it compiled
            }
        }
    }

    /** Reads the title pieces: each piece's condition and text template. */
    public static void visuals(ReadsCollector collector, TitleProgram visuals) {
        if (visuals == null) {
            return;
        }
        for (TitleProgram.Piece piece : visuals.pieces()) {
            collector.condition(piece.condition(), ROLE);
            if (piece.template() != null) {
                collector.template(piece.template().source(), ROLE);
            }
        }
    }

    private static void spec(ReadsCollector collector, ItemSpec spec) {
        switch (spec) {
            case ItemSpec.Plain plain -> { }
            case ItemSpec.Text text -> collector.template(text.template(), ROLE);
            case ItemSpec.Keyed keyed -> payload(collector, keyed.payload());
        }
    }

    /** Every string of a source payload is a template; a source's parameters are as templated as any other. */
    private static void payload(ReadsCollector collector, Object value) {
        switch (value) {
            case String text -> collector.template(text, ROLE);
            case Map<?, ?> map -> map.values().forEach(inner -> payload(collector, inner));
            case List<?> list -> list.forEach(inner -> payload(collector, inner));
            case null, default -> { }
        }
    }

    /** {@code pack:key} as written, or a key in the GUI's own pack (the {@code variable} list source's rule). */
    private static NamespacedId variable(String text, String pack) {
        return text.contains(":") ? NamespacedId.parse(text) : NamespacedId.of(pack, text);
    }
}
