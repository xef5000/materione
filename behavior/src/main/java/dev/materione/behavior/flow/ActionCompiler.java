package dev.materione.behavior.flow;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.ActionType;
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
 * type rejects the parameters, skips the step alone ({@code MTR-0057}).
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
        double chance = 1.0;
        long delayTicks = 0;
        for (var entry : shape.entrySet()) {
            String name = entry.getKey();
            if (name.equals(ActionStep.CHANCE_KEY)) {
                chance = ratio(entry.getValue(), path);
            } else if (name.equals(ActionStep.DELAY_KEY)) {
                delayTicks = ticks(entry.getValue(), path);
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
        return resolve(key, payload, chance, delayTicks, path);
    }

    private Step resolve(String key, Object payload, double chance, long delayTicks, StepPath path) {
        Optional<ActionType<?>> resolved = unit.context.lookup.action(key);
        if (resolved.isEmpty()) {
            unit.problem(DiagnosticCodes.LOGIC_STEP_REJECTED, Severity.WARN, path, "unknown action '" + key + "'",
                    "check the spelling or install the addon that provides it; only this action was skipped");
            throw Skip.step();
        }
        try {
            return bind(key, resolved.get(), payload, chance, delayTicks, path);
        } catch (RuntimeException e) {
            unit.problem(DiagnosticCodes.LOGIC_STEP_REJECTED, Severity.WARN, path,
                    "action '" + key + "' rejected: " + e.getMessage(),
                    "fix this action's parameters; only this action was skipped");
            throw Skip.step();
        }
    }

    /** Captures the type's parameter type so parse and bind agree on it. */
    private <P> Step bind(
            String key, ActionType<P> type, Object payload, double chance, long delayTicks, StepPath path) {
        var recorder = new RecordingExpressions();
        var reader = Shapes.reader(Shapes.paramsOf(key, type.describe().shorthand(), payload), recorder);
        ActionStep<P> step = ActionStep.bind(path, key, type, reader, chance, delayTicks);
        recorder.check(unit, path);
        return step;
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
