package dev.materione.behavior.logic.recipe;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Availability;
import dev.materione.behavior.logic.LogicLookup;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The warnings an expansion carries for actions this server cannot run right now (logic.md §30, {@code MTR-0088}):
 * a payment with no economy, a transfer with no proxy. The expansion is still valid, so the warning travels with it
 * and the author decides. Walks the steps the compiler would load, branches included, and reports each action once.
 */
final class RecipeAvailability {

    /** The keys whose values are more steps, not an action's parameters. */
    private static final Set<String> BRANCHES = Set.of("then", "else", "require", "choose");

    private RecipeAvailability() {}

    static List<Diagnostic> warnings(String recipe, List<Map<String, Object>> rules, LogicLookup lookup) {
        var out = new ArrayList<Diagnostic>();
        var seen = new HashSet<String>();
        for (Map<String, Object> rule : rules) {
            visit(rule.get("do"), recipe, lookup, seen, out);
        }
        return List.copyOf(out);
    }

    private static void visit(Object node, String recipe, LogicLookup lookup, Set<String> seen, List<Diagnostic> out) {
        if (node instanceof List<?> steps) {
            for (Object step : steps) {
                visit(step, recipe, lookup, seen, out);
            }
            return;
        }
        if (!(node instanceof Map<?, ?> step)) {
            return;
        }
        for (Map.Entry<?, ?> entry : step.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (BRANCHES.contains(key)) {
                visit(entry.getValue(), recipe, lookup, seen, out);
            } else {
                checkAction(key, recipe, lookup, seen, out);
            }
        }
    }

    private static void checkAction(
            String key, String recipe, LogicLookup lookup, Set<String> seen, List<Diagnostic> out) {
        Optional<ActionType<?>> action = lookup.action(key);
        if (action.isEmpty() || !seen.add(key)) {
            return;
        }
        Availability availability = availability(action.get());
        if (!availability.available()) {
            out.add(Diagnostic.warn(DiagnosticCodes.RECIPE_ACTION_UNAVAILABLE, "recipe '" + recipe + "' uses "
                    + key + ", which this server cannot run now: " + availability.reason()));
        }
    }

    private static Availability availability(ActionType<?> action) {
        try {
            Availability answer = action.availability();
            return answer == null ? Availability.AVAILABLE : answer;
        } catch (RuntimeException failure) {
            return Availability.unavailable("its availability check failed: " + failure.getMessage());
        }
    }
}
