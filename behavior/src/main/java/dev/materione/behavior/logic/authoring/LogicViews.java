package dev.materione.behavior.logic.authoring;

import dev.materione.api.logic.LogicAuthoringApi.FlowView;
import dev.materione.api.logic.LogicAuthoringApi.LogicView;
import dev.materione.api.logic.LogicAuthoringApi.NodePosition;
import dev.materione.api.logic.LogicAuthoringApi.RuleView;
import dev.materione.api.logic.LogicAuthoringApi.StepView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The typed view of a resource's logic (logic.md §7.2): rules, their steps as records, the named
 * flows and every node position. It reads the authored tree and never changes it; a malformed part
 * of the tree is shown as it is (a step with no kind is kind {@code ""}), because the canvas must be
 * able to show what the author wrote even when the compiler refuses it.
 */
final class LogicViews {

    /** The keys of a rule that are not trigger filters or options. */
    private static final Set<String> RULE_SHAPE = Set.of("on", "do", "if", "node");

    private LogicViews() {}

    /** The view of one resource's mapping. */
    static LogicView view(String pack, String path, String resourceId, int revision, Map<String, Object> resource) {
        var rules = new ArrayList<RuleView>();
        if (resource.get("behavior") instanceof List<?> entries) {
            for (int index = 0; index < entries.size(); index++) {
                if (entries.get(index) instanceof Map<?, ?> rule) {
                    rules.add(rule(index, StepShape.stringKeyed(rule)));
                }
            }
        }
        var flows = new ArrayList<FlowView>();
        if (resource.get("flows") instanceof Map<?, ?> named) {
            named.forEach((id, steps) -> {
                String flowId = String.valueOf(id);
                flows.add(new FlowView(flowId, steps(steps, "flows." + flowId)));
            });
        }
        return new LogicView(pack, path, resourceId, revision, rules, flows);
    }

    private static RuleView rule(int index, Map<String, Object> rule) {
        String path = "behavior." + index;
        var options = new LinkedHashMap<String, Object>();
        rule.forEach((key, value) -> {
            if (!RULE_SHAPE.contains(key)) {
                options.put(key, value);
            }
        });
        return new RuleView(
                index,
                path,
                rule.get("on") instanceof String on ? on : null,
                options,
                rule.get("if"),
                position(rule.get("node")),
                steps(rule.get("do"), path + ".do"));
    }

    /** The steps of one list, each at its path ({@code <list>.<index>}). */
    static List<StepView> steps(Object list, String listPath) {
        var out = new ArrayList<StepView>();
        if (list instanceof List<?> entries) {
            for (int index = 0; index < entries.size(); index++) {
                out.add(step(entries.get(index), listPath + "." + index));
            }
        }
        return out;
    }

    private static StepView step(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return new StepView(path, raw == null ? "" : String.valueOf(raw), null, Map.of(), null, Map.of());
        }
        Map<String, Object> map = StepShape.stringKeyed(rawMap);
        String kind = StepShape.displayKind(map);
        var options = new LinkedHashMap<String, Object>();
        map.forEach((key, value) -> {
            if (!key.equals(kind) && !StepShape.isBranch(key) && !key.equals("node")) {
                options.put(key, value);
            }
        });
        var branches = new LinkedHashMap<String, List<StepView>>();
        for (String key : List.of("then", "else", "require")) {
            if (map.get(key) instanceof List<?>) {
                branches.put(key, steps(map.get(key), path + "." + key));
            }
        }
        if (map.get("choose") instanceof List<?> cases) {
            for (int c = 0; c < cases.size(); c++) {
                if (cases.get(c) instanceof Map<?, ?> branch && branch.get("then") instanceof List<?>) {
                    String key = "choose." + c + ".then";
                    branches.put(key, steps(((Map<?, ?>) branch).get("then"), path + "." + key));
                }
            }
        }
        return new StepView(path, kind, map.get(kind), options, position(map.get("node")), branches);
    }

    /** A {@code node: {x, y}} as a position; absent or malformed is no position. */
    static NodePosition position(Object node) {
        if (node instanceof Map<?, ?> map && map.get("x") instanceof Number x && map.get("y") instanceof Number y) {
            return new NodePosition(x.doubleValue(), y.doubleValue());
        }
        return null;
    }
}
