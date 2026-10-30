package dev.materione.behavior.flow;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The named flows of one resource (logic.md §2.4): {@code flows: {<id>: [steps]}}. A flow compiles
 * the first time a {@code call} links to it, so cycles are found by the compile itself: asking for
 * a flow that is already being compiled is a cycle. Every call site holds the compiled flow
 * directly, so nothing is looked up at run time.
 *
 * <p>Flows never called are still compiled ({@link #compileAll}) so their problems are reported.
 * A flow that cannot load is remembered as broken and every {@code call} to it is reported and skips
 * the rule that holds it (§10).
 */
final class FlowSet {

    private static final Pattern FLOW_ID = Pattern.compile("[A-Za-z0-9_.-]+");

    private final CompileContext context;
    /** Roots a flow body's {@code ${...}} paths are checked against, or null when any trigger is unknown. */
    private final Set<String> roots;
    private final Map<String, Object> declared = new LinkedHashMap<>();
    private final Map<String, CompiledFlow> compiled = new HashMap<>();
    private final Set<String> broken = new HashSet<>();
    private final Deque<String> resolving = new ArrayDeque<>();

    /**
     * @param raw the {@code flows:} section as written; anything but a mapping declares nothing
     * @param roots the variable roots flow bodies may read ({@code args} included), or null
     */
    FlowSet(CompileContext context, Object raw, Set<String> roots) {
        this.context = context;
        this.roots = roots;
        if (raw == null) {
            return;
        }
        StepPath flowsPath = StepPath.of("flows");
        if (!(raw instanceof Map<?, ?> mapping)) {
            context.report(DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN, "flows", flowsPath,
                    "'flows' is not a mapping of flow id to steps",
                    "e.g. flows: { purchase: [ { message: \"hi\" } ] }");
            return;
        }
        for (var entry : Shapes.stringMap(mapping).entrySet()) {
            if (FLOW_ID.matcher(entry.getKey()).matches()) {
                declared.put(entry.getKey(), entry.getValue());
            } else {
                context.report(DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN, "flows",
                        flowsPath.child(entry.getKey()),
                        "flow id '" + entry.getKey() + "' is not a plain name",
                        "letters, digits, '_', '-' and '.'; the flow was ignored");
            }
        }
    }

    boolean isEmpty() {
        return declared.isEmpty();
    }

    /** Compiles the flows nothing called, so their problems are reported too. */
    void compileAll() {
        for (String id : declared.keySet()) {
            if (!compiled.containsKey(id) && !broken.contains(id)) {
                try {
                    link(id, StepPath.of("flows"), reporter());
                } catch (Skip ignored) {
                    // reported where it failed
                }
            }
        }
    }

    /**
     * The compiled flow {@code id}.
     *
     * @param callSite where the {@code call} is
     * @param caller the rule or flow holding the call, which a problem is reported against
     * @throws Skip with {@link Skip.Scope#UNIT} after reporting, when the flow cannot be linked
     */
    CompiledFlow link(String id, StepPath callSite, CompileUnit caller) {
        CompiledFlow done = compiled.get(id);
        if (done != null) {
            return done;
        }
        if (!declared.containsKey(id)) {
            throw unlinked(caller, callSite, "calls flow '" + id + "', which this resource does not declare",
                    declared.isEmpty()
                            ? "declare it under flows:; flows are shared within one resource only"
                            : "declared flows: " + declared.keySet() + "; flows are shared within one resource only");
        }
        if (broken.contains(id)) {
            throw unlinked(caller, callSite, "calls flow '" + id + "', which did not load",
                    "fix the flow's own problems reported with it");
        }
        if (resolving.contains(id)) {
            throw unlinked(caller, callSite, "call cycle: " + cycle(id),
                    "a flow cannot call itself, directly or through other flows");
        }
        resolving.push(id);
        try {
            CompiledFlow flow = compile(id);
            compiled.put(id, flow);
            return flow;
        } catch (Skip skip) {
            broken.add(id);
            throw skip;
        } finally {
            resolving.pop();
        }
    }

    private CompiledFlow compile(String id) {
        StepPath path = StepPath.of("flows", id);
        var unit = new CompileUnit(context, "flow '" + id + "'", roots,
                "that any trigger of this resource's rules provides (and not 'args')");
        var steps = new StepCompiler(unit, this);
        List<Step> body = steps.list(declared.get(id), path);
        boolean hadSteps = !Shapes.listOf(declared.get(id)).isEmpty();
        if (hadSteps && body.isEmpty()) {
            unit.problem(DiagnosticCodes.FLOW_CALL_INVALID, Severity.ERROR, path,
                    "none of its steps loaded", "fix the steps reported above; calls to this flow are skipped");
            throw Skip.unit();
        }
        int depth = steps.deepestCall();
        if (depth > CallStep.MAX_DEPTH) {
            unit.problem(DiagnosticCodes.FLOW_CALL_INVALID, Severity.ERROR, path,
                    "calls nest " + depth + " deep, past the limit of " + CallStep.MAX_DEPTH,
                    "flatten the chain of calls");
            throw Skip.unit();
        }
        return new CompiledFlow(id, path, body, depth);
    }

    private Skip unlinked(CompileUnit caller, StepPath callSite, String message, String hint) {
        caller.problem(DiagnosticCodes.FLOW_CALL_INVALID, Severity.ERROR, callSite, message, hint);
        return Skip.unit();
    }

    /** The unit problems about flows nothing called are reported against. */
    private CompileUnit reporter() {
        return new CompileUnit(context, "flows", roots, "");
    }

    private String cycle(String id) {
        var chain = new ArrayList<>(resolving);
        java.util.Collections.reverse(chain); // outermost first
        int from = chain.indexOf(id);
        var path = new ArrayList<>(chain.subList(from, chain.size()));
        path.add(id);
        return String.join(" -> ", path);
    }
}
