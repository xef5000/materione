package dev.materione.plugin.gui.rules;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.behavior.rule.Rule;
import dev.materione.content.gui.GuiDefinition;
import dev.materione.content.gui.GuiLayer;
import dev.materione.content.gui.logic.GuiRuleChecker;
import dev.materione.plugin.gui.open.OpenPlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The GUI half of rule checking (logic.md §10): compiles a GUI's rules with the flow compiler, so every
 * problem the engine finds comes back with its step path, and adds the checks only a GUI can make.
 * Registered with the gui type, so the pack compile and {@code gui.validate} report the same things.
 *
 * <p>The extra checks are warnings and never skip a rule: a {@code layer:} that names no layer of this
 * GUI (or a hidden one), a {@code slot:} outside the container, and a rule whose trigger no GUI fires.
 * Each would leave a rule that loads and never runs, which is the kind of mistake an author cannot see.
 */
public final class GuiRuleDiagnoser implements GuiRuleChecker {

    private final Supplier<LogicLookup> lookup;

    /**
     * @param lookup the registries rules resolve against, read at each check so an addon registered
     *     since boot is seen by the next reload
     */
    public GuiRuleDiagnoser(Supplier<LogicLookup> lookup) {
        this.lookup = lookup;
    }

    @Override
    public List<Diagnostic> check(GuiDefinition gui) {
        LogicLookup registries = lookup.get();
        GuiRuleSet.Compiled compiled = GuiRuleSet.compile(gui.id(), gui.rules(), registries);
        var out = new ArrayList<>(compiled.problems());
        OpenPlan open = OpenPlan.compile(gui.id(), gui.rules(), registries);
        if (open != null) {
            out.addAll(open.problems()); // the open block's own compile: arguments, requirement and deny (logic.md §6.1)
        }
        var layers = new HashMap<String, Boolean>();
        for (GuiLayer layer : gui.layers()) {
            layers.put(layer.id().toLowerCase(Locale.ROOT), layer.visible());
        }
        for (Rule rule : compiled.rules().all()) {
            trigger(gui, registries, rule, out);
            layerFilter(gui, rule, layers, out);
            slotFilter(gui, rule, out);
        }
        return out;
    }

    private static void trigger(GuiDefinition gui, LogicLookup registries, Rule rule, List<Diagnostic> out) {
        Optional<TriggerDescription> description = registries.trigger(rule.on().type());
        if (description.isPresent() && !description.get().firedBy().contains("gui")) {
            out.add(warn(gui, rule, "on",
                    "trigger '" + rule.on().type() + "' is never fired by a GUI, so this rule never runs",
                    "use a GUI trigger: gui.opening, gui.open, gui.close, gui.click, gui.click_outside or gui.tick"));
        }
    }

    private static void layerFilter(
            GuiDefinition gui, Rule rule, Map<String, Boolean> layers, List<Diagnostic> out) {
        for (String layer : rule.filters().get("layer").orElse(List.of())) {
            Boolean visible = layers.get(layer.toLowerCase(Locale.ROOT));
            if (visible == null) {
                out.add(warn(gui, rule, "layer",
                        "names layer '" + layer + "', which this GUI does not have, so the rule never runs",
                        "layers: " + String.join(", ", layers.keySet().stream().sorted().toList())));
            } else if (!visible) {
                out.add(warn(gui, rule, "layer",
                        "names layer '" + layer + "', which is hidden (visible: false), so the rule never runs",
                        "show the layer or remove the filter"));
            }
        }
    }

    private static void slotFilter(GuiDefinition gui, Rule rule, List<Diagnostic> out) {
        for (String slot : rule.filters().get("slot").orElse(List.of())) {
            int number;
            try {
                number = Integer.parseInt(slot);
            } catch (NumberFormatException e) {
                continue; // the filter's own type check already rejected it
            }
            if (number >= gui.rows() * 9) {
                out.add(warn(gui, rule, "slot",
                        "names slot " + number + ", outside this GUI's " + gui.rows() + " row(s) (0 to "
                                + (gui.rows() * 9 - 1) + "), so the rule never runs",
                        "slots count from 0 at the top-left, row by row"));
            }
        }
    }

    private static Diagnostic warn(GuiDefinition gui, Rule rule, String key, String message, String hint) {
        return new Diagnostic(
                DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN,
                gui.id() + " rule '" + rule.id() + "' at " + rule.path().child(key) + ": " + message,
                gui.id(), null, hint, rule.path().child(key).target());
    }
}
