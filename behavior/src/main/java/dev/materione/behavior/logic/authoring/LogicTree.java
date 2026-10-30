package dev.materione.behavior.logic.authoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Path navigation over a resource's authored tree (logic.md §8). A path is the dotted keys and list
 * indexes that reach a node in the YAML ({@code behavior.2.do.0.then.1}), so the tree is walked the
 * way a reader of the file would walk it: a mapping by key, a list by index. The tree is the working
 * copy of one resource; nothing here touches the document.
 *
 * <p>The step lists are the lists a step can sit in: a rule's {@code do}, the {@code then},
 * {@code else} and {@code require} branches of a step, and the body of a named flow. A
 * {@code behavior} list holds rules, and a {@code choose} list holds cases; neither takes a step.
 */
final class LogicTree {

    /** The keys whose lists hold steps (a flow's list is recognized by its position instead). */
    static final Set<String> STEP_LIST_KEYS = Set.of("do", "then", "else", "require");

    private LogicTree() {}

    /** The path's segments; a blank path or an empty segment is refused. */
    static List<String> segments(String path) {
        if (path == null || path.isBlank()) {
            throw LogicRefusal.step(path, "a step path is required (behavior.0.do.0, flows.name.0, ...)");
        }
        List<String> segments = List.of(path.trim().split("\\.", -1));
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw LogicRefusal.step(path, "the step path '" + path + "' has an empty segment");
            }
        }
        return segments;
    }

    /** The node a path reaches, refusing a path that leads nowhere. */
    static Object resolve(Map<String, Object> root, List<String> segments, String path) {
        Object current = root;
        for (String segment : segments) {
            current = child(current, segment, path);
        }
        return current;
    }

    /** One step down: a key of a mapping, or an index of a list. */
    static Object child(Object node, String segment, String path) {
        if (node instanceof Map<?, ?> map) {
            if (!map.containsKey(segment)) {
                throw LogicRefusal.step(path, "there is no '" + segment + "' at this point of the path");
            }
            return map.get(segment);
        }
        if (node instanceof List<?> list) {
            int index = index(segment, path);
            if (index >= list.size()) {
                throw LogicRefusal.step(path, "index " + segment + " is past the end (the list has "
                        + list.size() + " entries)");
            }
            return list.get(index);
        }
        throw LogicRefusal.step(path, "'" + segment + "' leads nowhere: the node is neither a list nor a mapping");
    }

    /** A list index from a segment; refused when it is not a non-negative whole number. */
    static int index(String segment, String path) {
        try {
            int index = Integer.parseInt(segment);
            if (index < 0) {
                throw new NumberFormatException(segment);
            }
            return index;
        } catch (NumberFormatException notAnIndex) {
            throw LogicRefusal.step(path, "'" + segment + "' is not a list index");
        }
    }

    /** The list a path names, which must exist and be a list. */
    static List<Object> listAt(Map<String, Object> root, List<String> segments, String path) {
        Object node = resolve(root, segments, path);
        if (!(node instanceof List<?>)) {
            throw LogicRefusal.step(path, "'" + String.join(".", segments) + "' is not a list");
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) node;
        return list;
    }

    /**
     * Whether the list reached by {@code parent} holds steps. A flow body is {@code flows.<id>}, a
     * branch is one of {@link #STEP_LIST_KEYS}; anything else (rules, cases) is not a step list.
     */
    static boolean holdsSteps(List<String> parent) {
        if (parent.size() == 2 && parent.get(0).equals("flows")) {
            return true;
        }
        return !parent.isEmpty() && STEP_LIST_KEYS.contains(parent.get(parent.size() - 1));
    }

    /** The parent list and insertion index an insertion path names; the list must hold steps. */
    static Insertion insertion(Map<String, Object> root, String path, boolean allowEnd) {
        List<String> segments = segments(path);
        List<String> parentSegments = segments.subList(0, segments.size() - 1);
        int index = index(segments.get(segments.size() - 1), path);
        if (parentSegments.isEmpty() || parentSegments.equals(List.of("behavior"))) {
            throw LogicRefusal.rule(path, "rules are added and moved with the rule edits, not as steps");
        }
        if (!holdsSteps(parentSegments)) {
            throw LogicRefusal.step(path, "'" + String.join(".", parentSegments) + "' does not hold steps");
        }
        List<Object> list = listAt(root, parentSegments, path);
        int limit = allowEnd ? list.size() : list.size() - 1;
        if (index > limit) {
            throw LogicRefusal.step(path, "step index " + index + " is outside 0.." + limit);
        }
        return new Insertion(list, index);
    }

    /** Where a step goes: its list and its index in it. */
    record Insertion(List<Object> list, int index) {}

    /** A step's own list and its index, for a step that already exists. */
    static Insertion existing(Map<String, Object> root, String path) {
        List<String> segments = segments(path);
        List<String> parentSegments = segments.subList(0, segments.size() - 1);
        if (parentSegments.isEmpty() || parentSegments.equals(List.of("behavior"))) {
            throw LogicRefusal.rule(path, "'" + path + "' is a rule, not a step");
        }
        if (!holdsSteps(parentSegments)) {
            throw LogicRefusal.step(path, "'" + String.join(".", parentSegments) + "' does not hold steps");
        }
        int index = index(segments.get(segments.size() - 1), path);
        List<Object> list = listAt(root, parentSegments, path);
        if (index >= list.size()) {
            throw LogicRefusal.step(path, "no step at index " + index + " (the list has " + list.size() + ")");
        }
        return new Insertion(list, index);
    }

    /** A copy of an authored value: maps become insertion-ordered maps, lists become lists, scalars stay. */
    static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            var out = new java.util.LinkedHashMap<String, Object>();
            map.forEach((key, child) -> out.put(String.valueOf(key), copy(child)));
            return out;
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<Object>(list.size());
            for (Object child : list) {
                out.add(copy(child));
            }
            return out;
        }
        return value;
    }
}
