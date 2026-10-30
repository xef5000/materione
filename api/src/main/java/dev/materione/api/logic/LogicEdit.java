package dev.materione.api.logic;

import java.util.List;
import java.util.Map;

/**
 * One structural edit to a resource's logic (logic.md §8). The set is closed: the node canvas and
 * the Script tab speak only these. Edits apply in list order to the file's working copy; a refused
 * edit refuses the whole request. Step paths are the {@code behavior.<i>...} and
 * {@code flows.<id>...} strings {@link LogicAuthoringApi} describes.
 */
public sealed interface LogicEdit
        permits LogicEdit.AddRule, LogicEdit.RemoveRules, LogicEdit.ReorderRules, LogicEdit.PatchRule,
        LogicEdit.InsertStep, LogicEdit.PatchStep, LogicEdit.MoveStep, LogicEdit.RemoveStep,
        LogicEdit.SetNodePosition, LogicEdit.AddFlow, LogicEdit.RemoveFlow, LogicEdit.RenameFlow {

    /** Inserts a rule at {@code index} (0 is the first). The rule's {@code on:} must name a known trigger. */
    record AddRule(int index, Map<String, Object> rule) implements LogicEdit {
        public AddRule {
            rule = LogicAuthoringApi.unmodifiable(rule);
        }
    }

    /** Removes the rules at these indexes, each named once in the current numbering. */
    record RemoveRules(List<Integer> indices) implements LogicEdit {
        public RemoveRules {
            indices = indices == null ? List.of() : List.copyOf(indices);
        }
    }

    /** Reorders the rules: {@code order} must be a permutation of every rule index. */
    record ReorderRules(List<Integer> order) implements LogicEdit {
        public ReorderRules {
            order = order == null ? List.of() : List.copyOf(order);
        }
    }

    /**
     * Sets and removes trigger filters and options of one rule. {@code set} writes each key (a null
     * value removes it); {@code unset} removes the named keys. {@code do} and {@code node} cannot be
     * patched this way: steps go through the step edits and positions through
     * {@link SetNodePosition}. {@code on} must name a known trigger.
     */
    record PatchRule(int index, Map<String, Object> set, List<String> unset) implements LogicEdit {
        public PatchRule {
            set = LogicAuthoringApi.unmodifiable(set);
            unset = unset == null ? List.of() : List.copyOf(unset);
        }
    }

    /** Inserts {@code step} at the position {@code path} names (its last index is the insertion index). */
    record InsertStep(String path, Object step) implements LogicEdit {}

    /** Sets and removes keys of the step at {@code path}; the step's branch keys cannot be patched. */
    record PatchStep(String path, Map<String, Object> set, List<String> unset) implements LogicEdit {
        public PatchStep {
            set = LogicAuthoringApi.unmodifiable(set);
            unset = unset == null ? List.of() : List.copyOf(unset);
        }
    }

    /**
     * Moves the step at {@code from} to the position {@code to} names. {@code to} is evaluated after
     * the step is taken out, so it names the list and index the step lands in. A step cannot move into
     * its own subtree.
     */
    record MoveStep(String from, String to) implements LogicEdit {}

    /** Removes the step at {@code path}. */
    record RemoveStep(String path) implements LogicEdit {}

    /**
     * Sets the canvas position of the rule or step at {@code path}. Both coordinates null removes the
     * position, and the canvas lays the node out automatically again (logic.md §12 decision 3).
     */
    record SetNodePosition(String path, Double x, Double y) implements LogicEdit {}

    /** Adds the named flow {@code id} with {@code steps}. The id must be new. */
    record AddFlow(String id, List<Object> steps) implements LogicEdit {
        public AddFlow {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /** Removes the named flow. A {@code call:} to it is then a compile error on the rule that holds it. */
    record RemoveFlow(String id) implements LogicEdit {}

    /** Renames a flow and rewrites every {@code call:} that names it in this resource. */
    record RenameFlow(String from, String to) implements LogicEdit {}
}
