package dev.materione.api.logic;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The typed authoring surface of the one behavior system (logic.md §8, api.md §3.3): edit the
 * {@code behavior:} rules and {@code flows:} of any resource that has them (gui, item, block,
 * furniture), read them back as structured records for the node canvas, validate a draft flow
 * without writing, and expand a recipe.
 *
 * <p>Steps are addressed by path strings: {@code behavior.2.do.0.then.1} is the second step of the
 * {@code then} branch of the first step of the third rule, and {@code flows.purchase.1} the second
 * step of the named flow {@code purchase}. A rule is {@code behavior.<i>}. An insertion path names
 * the position it inserts at (its last index).
 *
 * <p>Writes go through {@link dev.materione.api.authoring.AuthoringApi} (one revision check, one
 * transaction), and they rewrite the whole {@code behavior:} and {@code flows:} values. Unknown keys
 * inside a rule or a step survive; comments inside those blocks do not (the lossless writer
 * addresses mapping keys, not list items). An edit that changes nothing writes nothing.
 *
 * <p>Fail-soft: a refused edit is an {@link ApiResult} failure with the step path as its target; a
 * compile problem in an accepted edit comes back as a warning on the successful result.
 */
public interface LogicAuthoringApi {

    /** The resource's rules and named flows, typed. Never writes. */
    ApiResult<LogicView> get(String pack, String path);

    /**
     * Applies {@code request.edits()} in order, all or none, at {@code expectedRevision}. Returns the
     * typed view after the edits, with the compiler's diagnostics as warnings.
     */
    ApiResult<LogicView> edit(ActorContext actor, LogicEditRequest request);

    /**
     * The step-path diagnostics the real compiler reports for an unsaved draft. Nothing is written.
     * With a {@code trigger}, rules that name no {@code on:} take it.
     */
    ApiResult<List<Diagnostic>> validate(LogicDraft draft);

    /**
     * Expands a recipe into steps (logic.md §4.6). The recipe registry arrives with #222; until then
     * every recipe is refused as unknown. Never writes.
     */
    ApiResult<List<Map<String, Object>>> instantiate(InstantiateRequest request);

    /** A node position on the canvas (logic.md §12 decision 3). */
    record NodePosition(double x, double y) {}

    /**
     * One step as the typed view carries it. {@code kind} is the action's id as written (for an
     * action) or one of {@code if}, {@code require}, {@code choose}, {@code wait}, {@code stop},
     * {@code call}. {@code value} is what the kind's key holds (a parameter, a condition, a wait or a
     * call target), {@code options} are the step's other keys ({@code chance}, {@code delay},
     * {@code with}, ...). {@code branches} are the step lists under the step, keyed by the path
     * segments that reach them: {@code then}, {@code else}, {@code require}, and
     * {@code choose.<c>.then} for a switch case.
     */
    record StepView(
            String path,
            String kind,
            Object value,
            Map<String, Object> options,
            NodePosition node,
            Map<String, List<StepView>> branches) {

        public StepView {
            options = unmodifiable(options);
            branches = branches == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(branches));
        }
    }

    /**
     * One rule: its index and path, its {@code on:} selector, its other trigger filters and options
     * ({@code options}), its rule-level {@code if:} as authored, its node position and its steps.
     */
    record RuleView(
            int index,
            String path,
            String on,
            Map<String, Object> options,
            Object condition,
            NodePosition node,
            List<StepView> steps) {

        public RuleView {
            options = unmodifiable(options);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /** One named flow: its id and its steps. */
    record FlowView(String id, List<StepView> steps) {
        public FlowView {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /** A resource's logic as the canvas reads it. {@code revision} is the file's revision. */
    record LogicView(
            String pack,
            String path,
            String resourceId,
            int revision,
            List<RuleView> rules,
            List<FlowView> flows) {

        public LogicView {
            Objects.requireNonNull(pack, "pack");
            Objects.requireNonNull(resourceId, "resourceId");
            rules = rules == null ? List.of() : List.copyOf(rules);
            flows = flows == null ? List.of() : List.copyOf(flows);
        }
    }

    /** {@link #edit}: the resource's file at {@code expectedRevision}, and the edits in order. */
    record LogicEditRequest(String pack, String path, int expectedRevision, List<LogicEdit> edits) {
        public LogicEditRequest {
            edits = edits == null ? List.of() : List.copyOf(edits);
        }
    }

    /**
     * An unsaved draft of a resource's logic: its {@code behavior:} list and {@code flows:} map as
     * YAML would read them. {@code trigger}, when set, is the {@code on:} for rules that name none.
     */
    record LogicDraft(
            String pack,
            String resourceId,
            List<Object> behavior,
            Map<String, Object> flows,
            String trigger) {

        public LogicDraft {
            behavior = behavior == null ? List.of() : List.copyOf(behavior);
            flows = unmodifiable(flows);
        }
    }

    /** {@link #instantiate}: the recipe's id and its parameters. */
    record InstantiateRequest(String recipe, Map<String, Object> params) {
        public InstantiateRequest {
            params = unmodifiable(params);
        }
    }

    /** A map copied in its key order and made read-only; null is the empty map. */
    static <K, V> Map<K, V> unmodifiable(Map<K, V> source) {
        return source == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
