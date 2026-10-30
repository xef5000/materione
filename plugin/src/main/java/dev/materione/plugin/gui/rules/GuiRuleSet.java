package dev.materione.plugin.gui.rules;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import dev.materione.content.gui.logic.GuiRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A GUI's compiled rules, indexed for the runtime (logic.md §6.2). The flow compiler turns the
 * authored {@code behavior:} into {@link Rule}s once, when the live view is built; this class sorts
 * them by trigger so that firing a trigger is a list lookup, and so that a click finds the rules of
 * one layer without scanning the others.
 *
 * <p>Immutable, and swapped with the rest of the live view, so a click always runs against the rules
 * of the snapshot it started with.
 */
public final class GuiRuleSet {

    public static final String OPENING = "gui.opening";
    public static final String OPEN = "gui.open";
    public static final String CLOSE = "gui.close";
    public static final String CLICK = "gui.click";
    public static final String CLICK_OUTSIDE = "gui.click_outside";
    public static final String TICK = "gui.tick";

    /** The rules of a GUI that has none. */
    public static final GuiRuleSet EMPTY = new GuiRuleSet(List.of());

    private final List<Rule> all;
    private final Map<String, List<Rule>> byTrigger;
    private final Map<String, List<Rule>> clickByLayer;
    private final List<Rule> clickAnyLayer;
    private final int tickPeriod;

    private GuiRuleSet(List<Rule> rules) {
        this.all = List.copyOf(rules);
        var triggers = new HashMap<String, List<Rule>>();
        var layers = new HashMap<String, List<Rule>>();
        var anyLayer = new ArrayList<Rule>();
        int period = 0;
        for (Rule rule : all) {
            triggers.computeIfAbsent(rule.on().type(), ignored -> new ArrayList<>()).add(rule);
            if (rule.on().type().equals(CLICK)) {
                var layerFilter = rule.filters().get("layer");
                if (layerFilter.isEmpty()) {
                    anyLayer.add(rule);
                } else {
                    for (String layer : layerFilter.get()) {
                        layers.computeIfAbsent(layer.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(rule);
                    }
                }
            } else if (rule.on().type().equals(TICK)) {
                period = gcd(period, everyOf(rule));
            }
        }
        triggers.replaceAll((trigger, list) -> List.copyOf(list));
        layers.replaceAll((layer, list) -> List.copyOf(list));
        this.byTrigger = Map.copyOf(triggers);
        this.clickByLayer = Map.copyOf(layers);
        this.clickAnyLayer = List.copyOf(anyLayer);
        this.tickPeriod = period;
    }

    /**
     * The compiled rules of a GUI, with the problems the compile found. Never throws for content: a
     * rule that does not load is skipped, as for an item.
     *
     * @param lookup the registries actions, conditions and triggers resolve against
     */
    public static Compiled compile(NamespacedId owner, GuiRules rules, LogicLookup lookup) {
        if (rules.isEmpty()) {
            return new Compiled(EMPTY, List.of());
        }
        ParsedRules parsed = FlowCompiler.compile(owner, rules.behavior(), rules.flows(), lookup);
        return new Compiled(new GuiRuleSet(parsed.rules()), parsed.problems());
    }

    /** Every rule of the GUI, in authored order. */
    public List<Rule> all() {
        return all;
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }

    /** The rules of one trigger ({@code gui.open}), in authored order. */
    public List<Rule> rules(String trigger) {
        return byTrigger.getOrDefault(trigger, List.of());
    }

    /** The {@code gui.click} rules that name {@code layer} in their {@code layer:} filter. */
    public List<Rule> clickRules(String layer) {
        return clickByLayer.getOrDefault(layer.toLowerCase(Locale.ROOT), List.of());
    }

    /** The {@code gui.click} rules with no {@code layer:} filter: they answer a click no layer claimed. */
    public List<Rule> unboundClickRules() {
        return clickAnyLayer;
    }

    /** Whether any {@code gui.tick} rule exists. */
    public boolean ticks() {
        return tickPeriod > 0;
    }

    /** The greatest common divisor of every {@code gui.tick} rule's {@code every}: the ticker's period. */
    public int tickPeriod() {
        return tickPeriod;
    }

    /** A rule's {@code every:} in ticks, 0 when it has none. */
    public static int everyOf(Rule rule) {
        return rule.filters().get("every").map(values -> {
            try {
                return Integer.parseInt(values.get(0));
            } catch (NumberFormatException e) {
                return 0;
            }
        }).orElse(0);
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /** The compiled set and the compiler's problems. */
    public record Compiled(GuiRuleSet rules, List<Diagnostic> problems) {

        public Compiled {
            problems = List.copyOf(problems);
        }
    }
}
