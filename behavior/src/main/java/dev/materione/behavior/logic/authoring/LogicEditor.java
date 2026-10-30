package dev.materione.behavior.logic.authoring;

import dev.materione.api.logic.LogicEdit;
import dev.materione.behavior.logic.LogicLookup;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Applies the sealed {@link LogicEdit}s to the working copy of one resource (logic.md §8), in order.
 * Each edit either applies or throws a {@link LogicRefusal}; the caller discards the copy on a refusal,
 * so nothing is half-applied. The copy is the resource's whole mapping: {@code behavior} and
 * {@code flows} are the only keys this class writes, and every other key is left as it was.
 */
final class LogicEditor {

    /** A flow id as the compiler reads one (FlowSet). */
    static final Pattern FLOW_ID = Pattern.compile("[A-Za-z0-9_.-]+");

    private static final Set<String> RULE_PROTECTED = Set.of("do", "node");

    private final Map<String, Object> resource;
    private final LogicLookup lookup;

    LogicEditor(Map<String, Object> resource, LogicLookup lookup) {
        this.resource = resource;
        this.lookup = lookup;
    }

    /** Applies one edit. */
    void apply(LogicEdit edit) {
        switch (edit) {
            case LogicEdit.AddRule add -> addRule(add.index(), add.rule());
            case LogicEdit.RemoveRules remove -> removeRules(remove.indices());
            case LogicEdit.ReorderRules reorder -> reorderRules(reorder.order());
            case LogicEdit.PatchRule patch -> patchRule(patch.index(), patch.set(), patch.unset());
            case LogicEdit.InsertStep insert -> insertStep(insert.path(), insert.step());
            case LogicEdit.PatchStep patch -> patchStep(patch.path(), patch.set(), patch.unset());
            case LogicEdit.MoveStep move -> moveStep(move.from(), move.to());
            case LogicEdit.RemoveStep remove -> removeStep(remove.path());
            case LogicEdit.SetNodePosition node -> setNode(node.path(), node.x(), node.y());
            case LogicEdit.AddFlow add -> addFlow(add.id(), add.steps());
            case LogicEdit.RemoveFlow remove -> removeFlow(remove.id());
            case LogicEdit.RenameFlow rename -> renameFlow(rename.from(), rename.to());
        }
    }

    // ------------------------------------------------------------------
    // rules

    private void addRule(int index, Map<String, Object> rule) {
        List<Object> rules = rules(true);
        if (index < 0 || index > rules.size()) {
            throw LogicRefusal.rule("behavior." + index, "rule index " + index + " is outside 0.." + rules.size());
        }
        checkRule(rule, "behavior." + index);
        rules.add(index, LogicTree.copy(rule));
    }

    private void removeRules(List<Integer> indices) {
        List<Object> rules = rules(false);
        var seen = new HashSet<Integer>();
        for (int index : indices) {
            if (index < 0 || index >= rules.size()) {
                throw LogicRefusal.rule("behavior." + index, "there is no rule " + index);
            }
            if (!seen.add(index)) {
                throw LogicRefusal.rule("behavior." + index, "rule " + index + " is named twice");
            }
        }
        List<Integer> descending = new ArrayList<>(seen);
        descending.sort(Comparator.reverseOrder());
        for (int index : descending) {
            rules.remove(index);
        }
    }

    private void reorderRules(List<Integer> order) {
        List<Object> rules = rules(false);
        if (order.size() != rules.size()) {
            throw LogicRefusal.rule("behavior", "reorder names " + order.size() + " rules; there are " + rules.size());
        }
        var seen = new HashSet<Integer>();
        var next = new ArrayList<Object>(rules.size());
        for (int index : order) {
            if (index < 0 || index >= rules.size() || !seen.add(index)) {
                throw LogicRefusal.rule("behavior", "reorder must name every rule index once; " + index
                        + " is out of place");
            }
            next.add(rules.get(index));
        }
        rules.clear();
        rules.addAll(next);
    }

    private void patchRule(int index, Map<String, Object> set, List<String> unset) {
        String path = "behavior." + index;
        Map<String, Object> rule = rule(index, path);
        for (String key : set.keySet()) {
            if (RULE_PROTECTED.contains(key)) {
                throw LogicRefusal.rule(path + "." + key, "'" + key + "' is not patched on a rule; "
                        + (key.equals("do") ? "edit its steps" : "use setNodePosition"));
            }
        }
        for (String key : unset) {
            if (RULE_PROTECTED.contains(key) || key.equals("on")) {
                throw LogicRefusal.rule(path + "." + key, "'" + key + "' cannot be removed from a rule this way");
            }
        }
        var next = new LinkedHashMap<String, Object>(rule);
        set.forEach((key, value) -> {
            if (value == null) {
                next.remove(key);
            } else {
                next.put(key, LogicTree.copy(value));
            }
        });
        unset.forEach(next::remove);
        checkRule(next, path);
        rule.clear();
        rule.putAll(next);
    }

    /** A rule needs an {@code on:} that names a registered trigger (the compiler's own test). */
    private void checkRule(Map<String, Object> rule, String path) {
        if (!(rule.get("on") instanceof String on) || on.isBlank()) {
            throw LogicRefusal.rule(path, "a rule needs an on: trigger");
        }
        if (lookup.selector(on).isEmpty()) {
            throw LogicRefusal.rule(path + ".on", "unknown trigger '" + on + "'");
        }
    }

    // ------------------------------------------------------------------
    // steps

    private void insertStep(String path, Object step) {
        LogicTree.Insertion at = LogicTree.insertion(resource, path, true);
        StepShape.check(step, lookup, path);
        at.list().add(at.index(), LogicTree.copy(step));
    }

    @SuppressWarnings("unchecked")
    private void patchStep(String path, Map<String, Object> set, List<String> unset) {
        LogicTree.Insertion at = LogicTree.existing(resource, path);
        Object node = at.list().get(at.index());
        if (!(node instanceof Map<?, ?> raw)) {
            throw LogicRefusal.step(path, "a bare step has no fields to patch; replace it with a mapping");
        }
        Map<String, Object> step = StepShape.stringKeyed(raw);
        for (String key : set.keySet()) {
            if (StepShape.isBranch(key)) {
                throw LogicRefusal.step(path + "." + key, "'" + key + "' is edited through the step edits, "
                        + "not patched");
            }
        }
        for (String key : unset) {
            if (StepShape.isBranch(key)) {
                throw LogicRefusal.step(path + "." + key, "'" + key + "' is edited through the step edits");
            }
        }
        set.forEach((key, value) -> {
            if (value == null) {
                step.remove(key);
            } else {
                step.put(key, LogicTree.copy(value));
            }
        });
        unset.forEach(step::remove);
        StepShape.check(step, lookup, path);
        Map<String, Object> target = (Map<String, Object>) node;
        target.clear();
        target.putAll(step);
    }

    private void removeStep(String path) {
        LogicTree.Insertion at = LogicTree.existing(resource, path);
        at.list().remove(at.index());
    }

    /** Takes the step out, then inserts it where {@code to} names, evaluated against the tree without it. */
    private void moveStep(String from, String to) {
        if (from.equals(to)) {
            LogicTree.existing(resource, from); // still refuses a path that is not a step
            return;
        }
        if (to.startsWith(from + ".")) {
            throw LogicRefusal.step(to, "a step cannot move into its own subtree ('" + from + "')");
        }
        LogicTree.Insertion source = LogicTree.existing(resource, from);
        Object moving = source.list().remove(source.index());
        try {
            LogicTree.Insertion target = LogicTree.insertion(resource, to, true);
            target.list().add(target.index(), moving);
        } catch (LogicRefusal refused) {
            // the step goes back where it was, so a refused move leaves the working copy as it was
            source.list().add(source.index(), moving);
            throw refused;
        }
    }

    private void setNode(String path, Double x, Double y) {
        List<String> segments = LogicTree.segments(path);
        Object node = LogicTree.resolve(resource, segments, path);
        if (!(node instanceof Map<?, ?>)) {
            throw LogicRefusal.step(path, "only rules and steps have a position");
        }
        if (segments.size() == 2 && segments.get(0).equals("flows")) {
            throw LogicRefusal.step(path, "a named flow has no position of its own");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) node;
        if (x == null && y == null) {
            map.remove("node");
            return;
        }
        if (x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y)) {
            throw LogicRefusal.step(path, "a position needs finite x and y, or neither to clear it");
        }
        var position = new LinkedHashMap<String, Object>();
        position.put("x", x);
        position.put("y", y);
        map.put("node", position);
    }

    // ------------------------------------------------------------------
    // flows

    private void addFlow(String id, List<Object> steps) {
        String path = "flows." + id;
        checkFlowId(id, path);
        Map<String, Object> flows = flows(true);
        if (flows.containsKey(id)) {
            throw LogicRefusal.step(path, "a flow named '" + id + "' already exists");
        }
        for (int index = 0; index < steps.size(); index++) {
            StepShape.check(steps.get(index), lookup, path + "." + index);
        }
        flows.put(id, LogicTree.copy(steps));
    }

    private void removeFlow(String id) {
        Map<String, Object> flows = flows(false);
        if (!flows.containsKey(id)) {
            throw LogicRefusal.step("flows." + id, "there is no flow named '" + id + "'");
        }
        flows.remove(id);
        if (flows.isEmpty()) {
            resource.remove("flows");
        }
    }

    /** Renames the flow in place, keeping its position, and rewrites every call to it in this resource. */
    private void renameFlow(String from, String to) {
        String path = "flows." + from;
        Map<String, Object> flows = flows(false);
        if (!flows.containsKey(from)) {
            throw LogicRefusal.step(path, "there is no flow named '" + from + "'");
        }
        checkFlowId(to, "flows." + to);
        if (flows.containsKey(to)) {
            throw LogicRefusal.step("flows." + to, "a flow named '" + to + "' already exists");
        }
        var renamed = new LinkedHashMap<String, Object>();
        flows.forEach((key, steps) -> renamed.put(key.equals(from) ? to : key, steps));
        flows.clear();
        flows.putAll(renamed);
        rewriteCalls(from, to);
    }

    private void rewriteCalls(String from, String to) {
        Object rules = resource.get("behavior");
        if (rules instanceof List<?> list) {
            for (Object rule : list) {
                if (rule instanceof Map<?, ?> map && map.get("do") instanceof List<?> steps) {
                    rewriteCallsIn(steps, from, to);
                }
            }
        }
        if (resource.get("flows") instanceof Map<?, ?> flows) {
            for (Object steps : flows.values()) {
                if (steps instanceof List<?> list) {
                    rewriteCallsIn(list, from, to);
                }
            }
        }
    }

    private static void rewriteCallsIn(List<?> steps, String from, String to) {
        for (Object step : steps) {
            if (!(step instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) raw;
            if (map.containsKey("call")) {
                if (from.equals(map.get("call"))) {
                    map.put("call", to);
                } else if (map.get("call") instanceof Map<?, ?> call && from.equals(call.get("flow"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> target = (Map<String, Object>) call;
                    target.put("flow", to);
                }
            }
            for (String key : List.of("then", "else", "require")) {
                if (map.get(key) instanceof List<?> nested) {
                    rewriteCallsIn(nested, from, to);
                }
            }
            if (map.get("choose") instanceof List<?> cases) {
                for (Object each : cases) {
                    if (each instanceof Map<?, ?> branch && branch.get("then") instanceof List<?> nested) {
                        rewriteCallsIn(nested, from, to);
                    }
                }
            }
        }
    }

    private static void checkFlowId(String id, String path) {
        if (id == null || !FLOW_ID.matcher(id).matches()) {
            throw LogicRefusal.step(path, "a flow id must match [A-Za-z0-9_.-]+, not '" + id + "'");
        }
    }

    // ------------------------------------------------------------------
    // the containers

    /** The rule list; created empty when asked for and absent. */
    @SuppressWarnings("unchecked")
    private List<Object> rules(boolean create) {
        Object raw = resource.get("behavior");
        if (raw == null) {
            if (!create) {
                throw LogicRefusal.rule("behavior", "this resource has no behavior: rules");
            }
            var rules = new ArrayList<Object>();
            resource.put("behavior", rules);
            return rules;
        }
        if (!(raw instanceof List<?>)) {
            throw LogicRefusal.rule("behavior", "behavior: must be a list of rules");
        }
        return (List<Object>) raw;
    }

    /** The rule at {@code index}, refused when it is not one. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> rule(int index, String path) {
        List<Object> rules = rules(false);
        if (index < 0 || index >= rules.size()) {
            throw LogicRefusal.rule(path, "there is no rule " + index);
        }
        if (!(rules.get(index) instanceof Map<?, ?>)) {
            throw LogicRefusal.rule(path, "rule " + index + " is not a mapping");
        }
        return (Map<String, Object>) rules.get(index);
    }

    /** The flows map; created empty when asked for and absent. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> flows(boolean create) {
        Object raw = resource.get("flows");
        if (raw == null) {
            if (!create) {
                throw LogicRefusal.step("flows", "this resource has no flows:");
            }
            var flows = new LinkedHashMap<String, Object>();
            resource.put("flows", flows);
            return flows;
        }
        if (!(raw instanceof Map<?, ?>)) {
            throw LogicRefusal.step("flows", "flows: must be a mapping of flow id to steps");
        }
        return (Map<String, Object>) raw;
    }
}
