package dev.materione.behavior.logic.authoring;

import dev.materione.api.logic.ActionType;
import dev.materione.behavior.logic.LogicLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The structural check of one authored step, and the kind it names (logic.md §2.2). A step is a
 * mapping with exactly one kind: a control kind ({@code if}, {@code require}, {@code choose},
 * {@code wait}, {@code stop}, {@code call}) or one registered action. Anything else is an unknown kind
 * and the edit is refused. Bare names ({@code - gui.refresh}, {@code - stop}) are steps too.
 *
 * <p>This is the authoring check, not the compiler's: the compiler also checks the parameters and the
 * expressions, and reports them as diagnostics on the accepted edit.
 */
final class StepShape {

    /** The control kinds (logic.md §2.2). */
    static final Set<String> CONTROL = Set.of("if", "require", "choose", "wait", "stop", "call");

    /** Keys every step may carry besides its kind. */
    static final Set<String> COMMON = Set.of("chance", "delay", "node");

    /** Keys that belong to a control kind's shape rather than being an action's id. */
    private static final Set<String> SHAPE = Set.of("then", "else", "require", "choose", "with", "if", "wait",
            "stop", "call", "chance", "delay", "node");

    /** The branch keys: their lists are steps, edited through the step edits. */
    static final Set<String> BRANCHES = Set.of("then", "else", "require", "choose");

    /** A step's kind: the control kind or the action id it names, and whether it is control. */
    record Kind(String name, boolean control) {}

    private StepShape() {}

    /**
     * The kind an authored step names, refusing a step that names none, names two, or names an
     * action no registry knows. {@code path} is where the step sits, for the refusal.
     */
    static Kind check(Object step, LogicLookup lookup, String path) {
        if (step instanceof String bare) {
            return bareKind(bare, lookup, path);
        }
        if (!(step instanceof Map<?, ?> raw)) {
            throw LogicRefusal.step(path, "a step is a mapping or a bare name, not " + describe(step));
        }
        Map<String, Object> map = stringKeyed(raw);
        List<String> control = new ArrayList<>();
        for (String key : map.keySet()) {
            if (CONTROL.contains(key)) {
                control.add(key);
            }
        }
        if (control.size() > 1) {
            throw LogicRefusal.step(path, "a step names one kind, not " + control);
        }
        if (control.size() == 1) {
            String kind = control.get(0);
            requireControlShape(kind, map, path);
            return new Kind(kind, true);
        }
        List<String> actions = new ArrayList<>();
        for (String key : map.keySet()) {
            if (!SHAPE.contains(key) && !COMMON.contains(key)) {
                actions.add(key);
            }
        }
        if (actions.isEmpty()) {
            throw LogicRefusal.step(path, "the step names no kind: give it an action, or if, require, choose, "
                    + "wait, stop or call");
        }
        if (actions.size() > 1) {
            throw LogicRefusal.step(path, "a step names one action, not " + actions);
        }
        return actionKind(actions.get(0), lookup, path);
    }

    /** The kind a map names when it is read for display, without checking it: a control key, else the first action key. */
    static String displayKind(Map<String, Object> map) {
        for (String key : map.keySet()) {
            if (CONTROL.contains(key)) {
                return key;
            }
        }
        for (String key : map.keySet()) {
            if (!SHAPE.contains(key) && !COMMON.contains(key)) {
                return key;
            }
        }
        return "";
    }

    /** Whether a key is a branch of a step (its list is steps, or a switch's cases). */
    static boolean isBranch(String key) {
        return BRANCHES.contains(key);
    }

    private static Kind bareKind(String name, LogicLookup lookup, String path) {
        if (name.isBlank()) {
            throw LogicRefusal.step(path, "a bare step name must not be blank");
        }
        if (CONTROL.contains(name)) {
            return new Kind(name, true);
        }
        return actionKind(name, lookup, path);
    }

    private static Kind actionKind(String id, LogicLookup lookup, String path) {
        Optional<ActionType<?>> action = lookup.action(id);
        if (action.isEmpty()) {
            throw LogicRefusal.step(path, "unknown step kind '" + id + "': it is neither a control step nor a "
                    + "registered action");
        }
        return new Kind(id, false);
    }

    private static void requireControlShape(String kind, Map<String, Object> map, String path) {
        switch (kind) {
            case "if" -> {
                if (!(map.get("then") instanceof List<?>)) {
                    throw LogicRefusal.step(path, "an if step needs a then: list of steps");
                }
            }
            case "require" -> {
                if (!(map.get("require") instanceof List<?>)) {
                    throw LogicRefusal.step(path, "a require step needs a require: list of steps");
                }
            }
            case "choose" -> {
                if (!(map.get("choose") instanceof List<?>)) {
                    throw LogicRefusal.step(path, "a choose step needs a choose: list of cases");
                }
            }
            default -> {
                // wait, stop and call carry their own value; the compiler checks it
            }
        }
    }

    private static String describe(Object value) {
        return value == null ? "nothing" : value.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    static Map<String, Object> stringKeyed(Map<?, ?> source) {
        var out = new java.util.LinkedHashMap<String, Object>();
        source.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }
}
