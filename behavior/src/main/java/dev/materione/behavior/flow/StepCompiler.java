package dev.materione.behavior.flow;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.ValueExpression;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compiles step lists (logic.md §2.2) into the sealed {@link Step} tree. One compiler serves one
 * rule or one flow ({@link CompileUnit}); a {@code call} asks the {@link FlowSet} to link its target.
 *
 * <p>The fail-soft policy is in {@link #list}: a step the compiler understood but could not load
 * (unknown action, rejected parameters, bad condition) is dropped and its siblings load; a step
 * that is not a step skips the rule or flow. Two refinements keep a dropped step from changing what
 * the surviving ones mean: a {@code require} that lost a step fails closed, and a {@code choose} with
 * a bad condition is dropped as a whole.
 */
final class StepCompiler {

    private static final Set<String> CONTROL = Set.of("if", "require", "choose", "wait", "stop", "call");
    /** The keys each control step may carry beside its own (the editor hint {@code node} is stripped first). */
    private static final Map<String, Set<String>> COMPANIONS = Map.of(
            "if", Set.of("then", "else"),
            "require", Set.of("else"),
            "choose", Set.of("else"),
            "wait", Set.of(),
            "stop", Set.of(),
            "call", Set.of("with"));
    private static final Pattern ARG_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final CompileUnit unit;
    private final FlowSet flows;
    private final ConditionCompiler conditions;
    private final ActionCompiler actions;
    private int deepestCall;

    StepCompiler(CompileUnit unit, FlowSet flows) {
        this.unit = unit;
        this.flows = flows;
        this.conditions = new ConditionCompiler(unit);
        this.actions = new ActionCompiler(unit);
    }

    ConditionCompiler conditions() {
        return conditions;
    }

    /** How many nested calls the steps compiled so far can make at most. */
    int deepestCall() {
        return deepestCall;
    }

    /**
     * Compiles a list of steps; a single step written without the list is accepted.
     *
     * @throws Skip with {@link Skip.Scope#UNIT} when a step is not readable at all
     */
    List<Step> list(Object raw, StepPath listPath) {
        List<Object> items = Shapes.listOf(raw);
        var out = new ArrayList<Step>(items.size());
        for (int i = 0; i < items.size(); i++) {
            try {
                out.add(step(items.get(i), listPath.child(i)));
            } catch (Skip skip) {
                if (skip.scope() == Skip.Scope.UNIT) {
                    throw skip;
                }
                unit.context.skippedSteps++;
            }
        }
        return out;
    }

    private Step step(Object raw, StepPath path) {
        Map<String, Object> shape = shapeOf(raw, path);
        String control = controlKey(shape, path);
        if (control == null) {
            return actions.compile(shape, path);
        }
        Object payload = shape.get(control);
        return switch (control) {
            case "if" -> ifStep(shape, path);
            case "require" -> requireStep(shape, path);
            case "choose" -> chooseStep(shape, path);
            case "wait" -> waitStep(payload, path);
            case "stop" -> stopStep(payload, path);
            default -> callStep(shape, path);
        };
    }

    private Map<String, Object> shapeOf(Object raw, StepPath path) {
        if (raw instanceof String text && !text.isBlank()) {
            // a bare name: an action with no parameters (gui.refresh), or stop
            var shape = new LinkedHashMap<String, Object>();
            shape.put(text.trim(), null);
            return shape;
        }
        if (raw instanceof Map<?, ?> mapping) {
            Map<String, Object> shape = Shapes.stringMap(mapping);
            shape.remove("node"); // the editor's canvas position: accepted, ignored, never altered
            return shape;
        }
        throw unit.structural(path, "step is not a mapping",
                "each step is a mapping like { message: \"hi\" } or a bare action name");
    }

    /** The control kind a step map is, or null for an action; a map may be only one kind. */
    private String controlKey(Map<String, Object> shape, StepPath path) {
        String found = null;
        for (String key : shape.keySet()) {
            if (CONTROL.contains(key)) {
                if (found != null) {
                    throw unit.structural(path, "step mixes '" + found + "' and '" + key + "'",
                            "one step is one kind; nest the second inside the first");
                }
                found = key;
            }
        }
        if (found == null) {
            return null;
        }
        Set<String> allowed = COMPANIONS.get(found);
        for (String key : shape.keySet()) {
            if (!key.equals(found) && !allowed.contains(key)) {
                throw unit.structural(path, "'" + key + "' does not belong on a '" + found + "' step",
                        allowed.isEmpty()
                                ? "'" + found + "' takes no other keys"
                                : "'" + found + "' may carry " + allowed);
            }
        }
        return found;
    }

    // --- branches -------------------------------------------------------------------

    private Step ifStep(Map<String, Object> shape, StepPath path) {
        if (!shape.containsKey("then") && !shape.containsKey("else")) {
            throw unit.structural(path, "if has neither 'then' nor 'else'", "add the steps the branch runs");
        }
        Condition condition = null;
        Skip skip = null;
        try {
            condition = conditions.compile(shape.get("if"), path.child("if"));
        } catch (Skip failed) {
            if (failed.scope() == Skip.Scope.UNIT) {
                throw failed;
            }
            skip = failed;
        }
        List<Step> then = list(shape.get("then"), path.child("then"));
        List<Step> otherwise = list(shape.get("else"), path.child("else"));
        if (skip != null) {
            throw skip; // children were compiled only so their own problems are reported too
        }
        return new IfStep(path, condition, then, otherwise);
    }

    private Step requireStep(Map<String, Object> shape, StepPath path) {
        int skippedBefore = unit.context.skippedSteps;
        List<Step> body = list(shape.get("require"), path.child("require"));
        boolean blocked = unit.context.skippedSteps != skippedBefore;
        List<Step> otherwise = list(shape.get("else"), path.child("else"));
        if (blocked) {
            unit.problem(DiagnosticCodes.LOGIC_STEP_REJECTED, Severity.WARN, path,
                    "a step inside this require did not load, so the require fails every time",
                    "fix the step reported above; running the rest of the guard without it would let "
                            + "what follows happen unprotected");
        }
        return new RequireStep(path, body, otherwise, blocked);
    }

    private Step chooseStep(Map<String, Object> shape, StepPath path) {
        if (!(shape.get("choose") instanceof List<?> raw) || raw.isEmpty()) {
            throw unit.structural(path.child("choose"), "choose needs a list of { if, then } branches",
                    "e.g. choose: [ { if: \"...\", then: [ ... ] } ]");
        }
        StepPath casesPath = path.child("choose");
        var cases = new ArrayList<ChooseStep.Case>();
        boolean broken = false;
        for (int i = 0; i < raw.size(); i++) {
            try {
                cases.add(branch(raw.get(i), casesPath.child(i)));
            } catch (Skip skip) {
                if (skip.scope() == Skip.Scope.UNIT) {
                    throw skip;
                }
                broken = true;
            }
        }
        List<Step> otherwise = list(shape.get("else"), path.child("else"));
        if (broken) {
            // a branch that vanished would hand its turn to the next one: drop the whole choose
            throw Skip.step();
        }
        return new ChooseStep(path, cases, otherwise);
    }

    private ChooseStep.Case branch(Object raw, StepPath path) {
        if (!(raw instanceof Map<?, ?> mapping)) {
            throw unit.structural(path, "a choose branch is not a mapping", "each branch is { if: ..., then: [ ... ] }");
        }
        Map<String, Object> shape = Shapes.stringMap(mapping);
        shape.remove("node");
        if (!shape.containsKey("if") || !shape.containsKey("then") || shape.size() != 2) {
            throw unit.structural(path, "a choose branch has exactly 'if' and 'then'",
                    "use the choose step's own 'else' for the fallback");
        }
        Condition condition = null;
        Skip skip = null;
        try {
            condition = conditions.compile(shape.get("if"), path.child("if"));
        } catch (Skip failed) {
            if (failed.scope() == Skip.Scope.UNIT) {
                throw failed;
            }
            skip = failed;
        }
        List<Step> then = list(shape.get("then"), path.child("then"));
        if (skip != null) {
            throw skip;
        }
        return new ChooseStep.Case(path, condition, then);
    }

    // --- flow control ---------------------------------------------------------------

    private Step waitStep(Object payload, StepPath path) {
        Object ticks = payload instanceof Map<?, ?> mapping ? onlyKey(Shapes.stringMap(mapping), "ticks") : payload;
        if (!Shapes.isWhole(ticks) || ((Number) ticks).longValue() < 0
                || ((Number) ticks).longValue() > WaitStep.MAX_TICKS) {
            throw unit.structural(path.child("wait"),
                    "wait needs a whole number of ticks from 0 to " + WaitStep.MAX_TICKS,
                    "e.g. wait: 20 (one second)");
        }
        return new WaitStep(path, ((Number) ticks).longValue());
    }

    private Step stopStep(Object payload, StepPath path) {
        if (payload == null || Boolean.TRUE.equals(payload)) {
            return new StopStep(path, false, false);
        }
        if (payload instanceof Map<?, ?> mapping) {
            Map<String, Object> options = Shapes.stringMap(mapping);
            boolean cancel = flag(options, "cancel", path);
            boolean sessionRuns = flag(options, "cancel_session_runs", path);
            if (!Set.of("cancel", "cancel_session_runs").containsAll(options.keySet())) {
                throw unit.structural(path.child("stop"), "stop takes cancel and cancel_session_runs only",
                        "e.g. stop: { cancel: true }");
            }
            return new StopStep(path, cancel, sessionRuns);
        }
        throw unit.structural(path.child("stop"), "stop takes no value or a mapping",
                "e.g. stop, or stop: { cancel: true }");
    }

    private boolean flag(Map<String, Object> options, String key, StepPath path) {
        Object value = options.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw unit.structural(path.child("stop").child(key), key + " must be true or false", "e.g. " + key + ": true");
    }

    private Step callStep(Map<String, Object> shape, StepPath path) {
        Object target = shape.get("call");
        Object with = shape.get("with");
        if (target instanceof Map<?, ?> mapping) {
            if (shape.containsKey("with")) {
                throw unit.structural(path, "call has 'with' twice", "put 'with' in one place");
            }
            Map<String, Object> options = Shapes.stringMap(mapping);
            if (!Set.of("flow", "with").containsAll(options.keySet())) {
                throw unit.structural(path.child("call"), "call takes 'flow' and 'with' only",
                        "e.g. call: { flow: purchase, with: { price: 250 } }");
            }
            target = options.get("flow");
            with = options.get("with");
        }
        if (!(target instanceof String flowId) || flowId.isBlank()) {
            throw unit.structural(path.child("call"), "call needs the name of a flow",
                    "e.g. call: purchase, with the flow declared under flows:");
        }
        Map<String, ArgValue> args = args(with, path.child("with"));
        CompiledFlow flow = flows.link(flowId.trim(), path, unit);
        deepestCall = Math.max(deepestCall, flow.callDepth() + 1);
        return new CallStep(path, flowId.trim(), flow, args);
    }

    private Map<String, ArgValue> args(Object raw, StepPath path) {
        var out = new LinkedHashMap<String, ArgValue>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof Map<?, ?> mapping)) {
            throw unit.structural(path, "'with' must be a mapping of arguments", "e.g. with: { price: 250 }");
        }
        boolean failed = false;
        for (var entry : Shapes.stringMap(mapping).entrySet()) {
            StepPath at = path.child(entry.getKey());
            if (!ARG_NAME.matcher(entry.getKey()).matches()) {
                throw unit.structural(at, "argument name '" + entry.getKey() + "' is not a plain name",
                        "letters, digits and underscores; the callee reads it as args." + entry.getKey());
            }
            try {
                out.put(entry.getKey(), arg(entry.getValue(), at));
            } catch (Skip skip) {
                failed = true;
            }
        }
        if (failed) {
            throw Skip.step();
        }
        return out;
    }

    private ArgValue arg(Object value, StepPath path) {
        if (!(value instanceof String text) || !(text.contains("${") || text.contains("%"))) {
            return new ArgValue.Literal(Shapes.freeze(value));
        }
        var recorder = new RecordingExpressions(unit.context.lookup.functions());
        try {
            String inner = singleExpression(text);
            ArgValue compiled;
            if (inner != null) {
                ValueExpression expression = recorder.expression(inner);
                compiled = new ArgValue.Typed(expression);
            } else {
                TemplateText template = recorder.template(text);
                compiled = new ArgValue.Text(template);
            }
            recorder.check(unit, path);
            return compiled;
        } catch (IllegalArgumentException e) {
            unit.problem(DiagnosticCodes.EXPRESSION_INVALID, Severity.WARN, path, e.getMessage(),
                    "fix the expression syntax; the call was skipped");
            throw Skip.step();
        }
    }

    /** The expression inside a string that is exactly one {@code ${...}}, else null. */
    private static String singleExpression(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")
                && trimmed.indexOf('}') == trimmed.length() - 1 && trimmed.indexOf("${", 2) < 0) {
            return trimmed.substring(2, trimmed.length() - 1).trim();
        }
        return null;
    }

    private static Object onlyKey(Map<String, Object> mapping, String key) {
        return mapping.size() == 1 ? mapping.get(key) : null;
    }
}
