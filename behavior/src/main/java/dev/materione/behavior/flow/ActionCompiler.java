package dev.materione.behavior.flow;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ParseRejection;
import dev.materione.api.logic.Quantity;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.logic.TargetSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Compiles one action step (logic.md §2.2, §4.1): resolves the key through the registry and runs
 * the type's parse once, here, so a step holds a typed immutable parameter object and nothing reads
 * a raw map at run time.
 *
 * <p>An action map carries exactly one key that is not a universal property: the action id. The
 * universal properties are {@code chance}, {@code delay} and the editor hint {@code node}. A map
 * that breaks this skips the rule ({@code MTR-0021}); an id the registry does not know, or whose
 * type rejects the parameters, skips the step alone ({@code MTR-0057}). A literal {@code chance} or
 * {@code delay} is checked here; a template is compiled with the action's own parameters and checked
 * as the step runs (logic.md section 42, #470).
 */
final class ActionCompiler {

    private final CompileUnit unit;

    ActionCompiler(CompileUnit unit) {
        this.unit = unit;
    }

    /**
     * @param shape the step as the author wrote it, already stripped of {@code node}
     * @throws Skip after reporting
     */
    Step compile(Map<String, Object> shape, StepPath path) {
        String key = null;
        Object payload = null;
        Object chance = 1.0;
        Object delay = 0L;
        for (var entry : shape.entrySet()) {
            String name = entry.getKey();
            if (name.equals(ActionStep.CHANCE_KEY)) {
                chance = timing(entry.getValue(), path, ActionStep.CHANCE_KEY);
            } else if (name.equals(ActionStep.DELAY_KEY)) {
                delay = timing(entry.getValue(), path, ActionStep.DELAY_KEY);
            } else if (key == null) {
                key = name;
                payload = entry.getValue();
            } else {
                throw unit.structural(path, "bad action: multiple action keys ('" + key + "', '" + name + "')",
                        "one step runs one action; put the second in its own step");
            }
        }
        if (key == null) {
            throw unit.structural(path, "bad action: no action type key",
                    "each action is a mapping like { run_command: \"say hi\" }");
        }
        return resolve(key, payload, chance, delay, path);
    }

    /**
     * The value of a universal key, checked when it is a literal: a template is returned as written and compiled with
     * the action ({@link #bind}). A literal is the number the key always took; anything else is refused at load.
     */
    private Object timing(Object value, StepPath path, String key) {
        if (isTemplate(value)) {
            return value;
        }
        return key.equals(ActionStep.CHANCE_KEY) ? ratio(value, path) : ticks(value, path);
    }

    /** True for a string holding a template: the one shape a universal key reads as an expression. */
    static boolean isTemplate(Object value) {
        return value instanceof String text && (text.contains("${") || text.contains("%"));
    }

    /**
     * The ticks of a {@code wait} whose value is a template (logic.md section 42, #470): compiled with a recorder of its own,
     * so its roots are checked at load like any expression; the value is read when the step runs.
     */
    Quantity waitTicks(Object ticks, StepPath path) {
        var recorder = new RecordingExpressions(unit.context.lookup.functions());
        try {
            var reader = Shapes.reader(new LinkedHashMap<>(Map.of("ticks", ticks)), recorder, unit.context.environment);
            Quantity quantity = Quantity.integerOr(reader, "ticks", 0, 0, (int) WaitStep.MAX_TICKS);
            recorder.check(unit, path);
            return quantity;
        } catch (IllegalArgumentException e) {
            unit.problem(DiagnosticCodes.EXPRESSION_INVALID, Severity.WARN, path, e.getMessage(),
                    "fix the expression syntax; the wait was skipped");
            throw Skip.step();
        }
    }

    private Step resolve(String key, Object payload, Object chance, Object delay, StepPath path) {
        Optional<ActionType<?>> resolved = unit.context.lookup.action(key);
        if (resolved.isEmpty()) {
            unit.problem(DiagnosticCodes.LOGIC_STEP_REJECTED, Severity.WARN, path, "unknown action '" + key + "'",
                    "check the spelling or install the addon that provides it; only this action was skipped");
            throw Skip.step();
        }
        try {
            return bind(key, resolved.get(), payload, chance, delay, path);
        } catch (RuntimeException e) {
            // a type that knows its own problem names its code (logic.md §3.2); everything else is MTR-0057
            if (e instanceof ParseRejection rejection) {
                unit.problem(rejection.code(), rejection.code().defaultSeverity(), path,
                        "action '" + key + "' rejected: " + e.getMessage(),
                        "fix this action's parameters; only this action was skipped");
            } else {
                unit.problem(DiagnosticCodes.LOGIC_STEP_REJECTED, Severity.WARN, path,
                        "action '" + key + "' rejected: " + e.getMessage(),
                        "fix this action's parameters; only this action was skipped");
            }
            throw Skip.step();
        }
    }

    /** Captures the type's parameter type so parse and bind agree on it. */
    private <P> Step bind(String key, ActionType<P> type, Object payload, Object chance, Object delay, StepPath path) {
        var recorder = new RecordingExpressions(unit.context.lookup.functions());
        ActionDescription description = type.describe();
        var reader = Shapes.reader(
                Shapes.paramsOf(key, description.shorthand(), payload), recorder, unit.context.environment);
        // the universal keys are read the way a parameter is: one reader over both, so a template compiles with the
        // same recorder and its roots are checked with the action's own
        var timings = new LinkedHashMap<String, Object>();
        timings.put(ActionStep.CHANCE_KEY, chance);
        timings.put(ActionStep.DELAY_KEY, delay);
        var timingReader = Shapes.reader(timings, recorder, unit.context.environment);
        Quantity chanceQuantity = Quantity.decimalOr(timingReader, ActionStep.CHANCE_KEY, 1d, 0d, 1d);
        Quantity delayQuantity = Quantity.integerOr(timingReader, ActionStep.DELAY_KEY, 0, 0, (int) WaitStep.MAX_TICKS);
        ActionStep<P> step = ActionStep.bind(path, key, type, reader, chanceQuantity, delayQuantity);
        step = target(step, description, reader);
        assert Shapes.declaresExpressions(type.describe().self().parameters(), reader.expressionKeys())
                : "action '" + key + "' binds " + reader.expressionKeys()
                        + " as expressions; its description must declare each with acceptingExpression()";
        recorder.check(unit, path);
        unit.checkRequiredRoots(reader.requiredRoots(), path);
        return step;
    }

    /**
     * Reads the step's {@code target:} (logic.md §39) once, here, for an action that declared itself targetable. An
     * action that did not, and that has no parameter of its own called {@code target}, is told so: a target it would
     * silently ignore is worse than a skipped step.
     */
    private <P> ActionStep<P> target(ActionStep<P> step, ActionDescription description, ParamReader reader) {
        if (!description.isTargetable()) {
            boolean ownParameter = description.self().parameters().stream()
                    .anyMatch(parameter -> parameter.id().equals(TargetSpec.KEY));
            if (reader.has(TargetSpec.KEY) && !ownParameter) {
                throw new IllegalArgumentException("this action cannot act on a target; only the actions the "
                        + "vocabulary marks targetable take one");
            }
            return step;
        }
        TargetSpec spec = TargetSpec.read(reader).orElse(null);
        if (spec instanceof TargetSpec.Event && !unit.mayRead("target")) {
            throw new IllegalArgumentException("target: event.target needs a trigger that carries an entity "
                    + "(entity.hit or entity.kill); this rule's trigger does not");
        }
        var admits = java.util.EnumSet.noneOf(TargetKind.class);
        for (TargetKind kind : TargetKind.values()) {
            if (description.admits(kind)) {
                admits.add(kind);
            }
        }
        return step.withTarget(spec, admits);
    }

    private double ratio(Object value, StepPath path) {
        if (value instanceof Number number && number.doubleValue() >= 0 && number.doubleValue() <= 1) {
            return number.doubleValue();
        }
        throw unit.structural(path.child(ActionStep.CHANCE_KEY),
                "bad action: 'chance' must be between 0 and 1", "e.g. chance: 0.25");
    }

    private long ticks(Object value, StepPath path) {
        if (Shapes.isWhole(value) && ((Number) value).longValue() >= 0
                && ((Number) value).longValue() <= WaitStep.MAX_TICKS) {
            return ((Number) value).longValue();
        }
        throw unit.structural(path.child(ActionStep.DELAY_KEY),
                "bad action: 'delay' must be a whole number of ticks from 0 to " + WaitStep.MAX_TICKS,
                "e.g. delay: 20 (one second)");
    }
}
