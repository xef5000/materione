package dev.materione.content.gui.logic;

import dev.materione.core.document.LosslessYaml;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The logic a GUI resource carries (logic.md §2, §6): its {@code behavior:} rules and its named
 * {@code flows:}, exactly as authored. The same two keys an item, block or furniture definition has,
 * so a GUI button runs the same flow an item right-click runs (D4).
 *
 * <p>This class only reads the <em>shape</em> of the two sections. Turning them into typed rules is
 * the flow compiler's job ({@code behavior}, which this Bukkit-free and registry-free module cannot
 * see), and it happens twice from the same values: in the diagnose stage through a
 * {@link GuiRuleChecker}, so a bad rule is reported with its step path where every GUI problem is,
 * and in the runtime view, so the live GUI holds the compiled rules. Nothing is rewritten, so a
 * load, compile and untouched save is byte-identical (master §5.6).
 *
 * @param behavior the {@code behavior:} entries in authored order. A rule mapping is returned with
 *        string keys (YAML 1.1 reads a bare {@code on:} as a boolean key); an entry that is not a
 *        mapping stays as written so the compiler can name its index
 * @param flows the {@code flows:} value as authored (a mapping of flow id to steps), or null
 * @param problems the section-level shape problems, in the order they were found
 */
public record GuiRules(List<Object> behavior, Object flows, List<Problem> problems) {

    /** A GUI with no logic. */
    public static final GuiRules EMPTY = new GuiRules(List.of(), null, List.of());

    public GuiRules {
        behavior = List.copyOf(behavior);
        problems = List.copyOf(problems);
    }

    /**
     * One shape problem.
     *
     * @param field the top-level key it is about ({@code behavior} or {@code flows})
     * @param message what is wrong, in the author's terms
     */
    public record Problem(String field, String message) {}

    /** Reads the two sections of a GUI's merged definition. */
    public static GuiRules parse(Map<String, Object> definition) {
        var problems = new ArrayList<Problem>();
        var rules = new ArrayList<Object>();
        Object behavior = definition.get("behavior");
        if (behavior instanceof List<?> entries) {
            for (Object entry : entries) {
                rules.add(entry instanceof Map<?, ?> mapping ? LosslessYaml.stringKeyed(mapping) : entry);
            }
        } else if (behavior != null) {
            problems.add(new Problem("behavior", "behavior must be a list of rules"));
        }
        Object flows = definition.get("flows");
        if (flows != null && !(flows instanceof Map<?, ?>)) {
            problems.add(new Problem("flows", "flows must be a mapping of flow name to steps"));
            flows = null;
        }
        if (rules.isEmpty() && flows == null && problems.isEmpty()) {
            return EMPTY;
        }
        return new GuiRules(rules, flows, problems);
    }

    /** Whether the GUI declares no rules and no flows. */
    public boolean isEmpty() {
        return behavior.isEmpty() && flows == null;
    }
}
