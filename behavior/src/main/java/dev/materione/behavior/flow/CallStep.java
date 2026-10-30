package dev.materione.behavior.flow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code call: <flow-id>} or {@code call: {flow, with: {...}}}: runs a named flow of the same
 * resource inline (logic.md §2.4). The {@code with} values are evaluated in the caller's scope when
 * the call is reached and become {@code args.*} inside the callee, and only there: a nested call
 * has its own {@code args}. The callee shares the run's local variables, its budget and its fate:
 * a {@code stop}, or a failed {@code require}, inside it ends the whole run.
 *
 * <p>The target is resolved at load. A call to an unknown flow, a cycle, or a chain deeper than the
 * call depth limit never reaches this class: the compiler skips the rule that holds it.
 *
 * @param path where the step was written
 * @param flowId the name the author wrote
 * @param flow the resolved flow
 * @param args the arguments, in authored order
 */
public record CallStep(StepPath path, String flowId, CompiledFlow flow, Map<String, ArgValue> args)
        implements Step {

    /** The deepest chain of nested calls a run may make (logic.md §5.3). */
    public static final int MAX_DEPTH = 16;

    public CallStep {
        args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    @Override
    public String kind() {
        return "call";
    }
}
