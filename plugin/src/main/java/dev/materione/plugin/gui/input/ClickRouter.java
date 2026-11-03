package dev.materione.plugin.gui.input;

import dev.materione.behavior.rule.FilterFacts;
import dev.materione.behavior.rule.Rule;
import dev.materione.plugin.gui.GuiLookup;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.event.GuiEvents;
import dev.materione.plugin.gui.list.CompiledListLayer;
import dev.materione.plugin.gui.render.Rendered;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides which rules a click on a GUI runs (logic.md §6.2): the topmost showing layer that has a
 * {@code gui.click} rule matching the click. The compiled {@code ClickMap} gives the layers over the
 * slot, topmost first; for each one the router looks up that layer's rules and keeps those whose
 * filters ({@code slot}, {@code click}) match; the first layer with a match wins and the layers
 * under it never see the click. Rules with no {@code layer:} filter answer a click no layer claimed.
 * There is one map lookup per click and no geometry.
 *
 * <p>The router decides and hands the rules to {@link GuiEvents}; it holds no Bukkit type, so the
 * routing table is unit-tested directly.
 */
public final class ClickRouter {

    /** What a routed click ran. */
    public record Routed(String layer, List<Rule> rules) {

        /** A click nothing answered. */
        public static final Routed NONE = new Routed("", List.of());

        public Routed {
            rules = List.copyOf(rules);
        }

        public boolean handled() {
            return !rules.isEmpty();
        }
    }

    private final GuiEvents events;
    private final LayerVisibility visibility;
    private final InputGuard guard;

    public ClickRouter(GuiLookup guis, GuiEvents events, LayerVisibility visibility, InputGuard guard) {
        Objects.requireNonNull(guis, "guis");
        this.events = Objects.requireNonNull(events, "events");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /** Routes a click on a slot of the container and runs the rules it reaches. */
    public Routed route(GuiClick click) {
        GuiSession session = click.session();
        if (!session.isOpen()) {
            return Routed.NONE;
        }
        Optional<RuntimeGuis.Live> found = session.displayed();
        if (found.isEmpty()) {
            return Routed.NONE;
        }
        RuntimeGuis.Live gui = found.get();
        for (String layer : gui.clicks().layersAt(click.slot())) {
            if (!visibility.visible(session, layer)
                    || (session.rendered().coverage().containsKey(layer)
                        && !session.rendered().coverage().get(layer).contains(click.slot()))) {
                continue;
            }
            // a list answers only the slots it shows an entry in: an empty slot runs none of its rules (logic.md §6.7)
            Map<String, Object> entry = null;
            if (isList(gui, layer)) {
                Optional<Rendered.ListRender.Shown> shown = session.rendered().lists().shownAt(click.slot())
                        .filter(showing -> showing.layer().equals(layer));
                if (shown.isEmpty()) {
                    continue;
                }
                entry = shown.get().entry();
            }
            List<Rule> matching = matching(gui.logic().clickRules(layer), facts(layer, click));
            if (!matching.isEmpty()) {
                dispatch(click, layer, matching, entry);
                return new Routed(layer, matching);
            }
        }
        List<Rule> fallback = matching(gui.logic().unboundClickRules(), facts("", click));
        if (fallback.isEmpty()) {
            return Routed.NONE;
        }
        dispatch(click, "", fallback, null);
        return new Routed("", fallback);
    }

    private static boolean isList(RuntimeGuis.Live gui, String layer) {
        for (CompiledListLayer list : gui.lists()) {
            if (list.layerId().equals(layer)) {
                return true;
            }
        }
        return false;
    }

    /** Runs the {@code gui.click_outside} rules for a click outside the window. */
    public void outside(Viewer viewer, GuiSession session, ClickKind kind) {
        if (!session.isOpen()) {
            return;
        }
        session.displayed().ifPresent(gui ->
                guard.during(viewer.id(), () -> events.outside(viewer, session, gui, kind)));
    }

    private void dispatch(GuiClick click, String layer, List<Rule> rules, Map<String, Object> entry) {
        guard.during(click.viewer().id(), () -> events.click(
                click.viewer(), click.session(), rules, layer, click.slot(), click.kind(), click.key(), entry));
    }

    private static List<Rule> matching(List<Rule> candidates, FilterFacts facts) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Rule> out = null;
        for (Rule rule : candidates) {
            if (rule.filters().matches(facts)) {
                if (out == null) {
                    out = new java.util.ArrayList<>(candidates.size());
                }
                out.add(rule);
            }
        }
        return out == null ? List.of() : out;
    }

    private static FilterFacts facts(String layer, GuiClick click) {
        return key -> switch (key) {
            case "layer" -> layer.isEmpty() ? null : layer;
            case "slot" -> Integer.toString(click.slot());
            case "click" -> click.kind().id();
            default -> null;
        };
    }
}
