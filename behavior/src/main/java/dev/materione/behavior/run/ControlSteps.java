package dev.materione.behavior.run;

import dev.materione.api.logic.Cancellation;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.ReadRetiredException;
import dev.materione.behavior.flow.ArgValue;
import dev.materione.behavior.flow.CallStep;
import dev.materione.behavior.flow.ChooseStep;
import dev.materione.behavior.flow.Condition;
import dev.materione.behavior.flow.ResumableConditions;
import dev.materione.behavior.flow.IfStep;
import dev.materione.behavior.flow.RequireStep;
import dev.materione.behavior.flow.Step;
import dev.materione.behavior.flow.StepPath;
import dev.materione.behavior.flow.StopStep;
import dev.materione.behavior.flow.WaitStep;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * The control steps of the interpreter (logic.md §2.2): {@code if}, {@code choose}, {@code require},
 * {@code wait}, {@code stop} and {@code call}, and the condition evaluation they share. Split from
 * {@link FlowInterpreter} and {@link FlowContinuations} to keep each within the size guard
 * (logic.md §9 rule 4); the interpreter supplies the primitives below.
 */
abstract class ControlSteps {

    final RunScheduler scheduler;
    final RunBudget budget;
    final RunReporter reporter;

    ControlSteps(RunScheduler scheduler, RunBudget budget, RunReporter reporter) {
        this.scheduler = scheduler;
        this.budget = budget;
        this.reporter = reporter;
    }

    // --- primitives the interpreter supplies --------------------------------------------

    abstract void consume(FlowRun run, Frame frame);

    abstract void pop(FlowRun run);

    abstract void push(FlowRun run, Frame frame);

    abstract boolean failure(FlowRun run, StepPath path, String message, boolean thrown);

    abstract boolean park(FlowRun run, StepPath path, Threading thread, long ticks, boolean timed);

    abstract void abort(FlowRun run, StepPath path, String reason);

    abstract void finish(FlowRun run, RunOutcome outcome, String detail);

    abstract void emit(FlowRun run, StepPath path, TraceEvent.Type type, String detail);

    abstract int cancel(Object cancelKey);

    abstract boolean await(FlowRun run, StepPath path, Supplier<? extends CompletionStage<?>> operation);

    static String reasonOf(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    // --- steps ----------------------------------------------------------------------

    final boolean branch(FlowRun run, Frame frame, IfStep step) {
        if (!await(run, step.path(), () -> ResumableConditions.test(step.condition(), run.view(), run.reads))) {
            return false;
        }
        boolean holds = holds(run, step.path());
        consume(run, frame);
        if (run.finished.get()) {
            return false;
        }
        emit(run, step.path(), TraceEvent.Type.BRANCH, holds ? "then" : "else");
        enter(run, frame, holds ? step.then() : step.otherwise());
        return true;
    }

    final boolean choose(FlowRun run, Frame frame, ChooseStep step) {
        for (; run.choiceIndex < step.cases().size(); run.choiceIndex++) {
            int i = run.choiceIndex;
            ChooseStep.Case branch = step.cases().get(i);
            if (!await(run, branch.path(), () -> ResumableConditions.test(branch.condition(), run.view(), run.reads))) {
                return false;
            }
            boolean holds = holds(run, branch.path());
            if (run.finished.get()) {
                return false;
            }
            if (holds) {
                consume(run, frame);
                emit(run, step.path(), TraceEvent.Type.BRANCH, "case " + i);
                enter(run, frame, branch.then());
                return true;
            }
        }
        consume(run, frame);
        emit(run, step.path(), TraceEvent.Type.BRANCH, "else");
        enter(run, frame, step.otherwise());
        return true;
    }

    final boolean require(FlowRun run, Frame frame, RequireStep step) {
        consume(run, frame);
        if (step.blocked()) {
            return failRequireStep(run, step, frame.args, "a step in the require did not load");
        }
        if (!step.steps().isEmpty()) {
            push(run, Frame.require(step, frame.args));
        }
        return true;
    }

    /** Unwinds to {@code guard} (the failing step's nearest require) and fails it. */
    final boolean failRequire(FlowRun run, Frame guard, String reason) {
        while (run.stack.peek() != guard) {
            pop(run);
        }
        pop(run);
        return failRequireStep(run, guard.require, guard.args, reason);
    }

    final boolean failRequireStep(FlowRun run, RequireStep step, Map<String, Object> args, String reason) {
        emit(run, step.path(), TraceEvent.Type.REQUIRE_FAILED, reason);
        run.armed = null;
        run.stage = 0;
        run.hopped = false;
        if (step.otherwise().isEmpty()) {
            finish(run, RunOutcome.REQUIRE_FAILED, reason);
            return false;
        }
        push(run, Frame.failElse(step, args));
        return true;
    }

    final boolean wait(FlowRun run, Frame frame, WaitStep step) {
        consume(run, frame);
        return !park(run, step.path(), Threading.PLAYER_REGION, step.ticks(), true);
    }

    final boolean stop(FlowRun run, Frame frame, StopStep step) {
        consume(run, frame);
        if (step.cancel()) {
            run.view().facet(Cancellation.FACET).ifPresent(Cancellation::request);
        }
        if (step.cancelSessionRuns() && run.owner().cancelKey() != null) {
            cancel(run.owner().cancelKey());
        }
        finish(run, RunOutcome.STOPPED, "stop");
        return false;
    }

    final boolean call(FlowRun run, Frame frame, CallStep step) {
        if (run.calls >= budget.maxCallDepth()) {
            abort(run, step.path(), "calls nested deeper than " + budget.maxCallDepth());
            return false;
        }
        if (run.callArgs == null) {
            run.callArgs = new LinkedHashMap<>();
        }
        var entries = List.copyOf(step.args().entrySet());
        try {
            for (; run.callArgIndex < entries.size(); run.callArgIndex++) {
                var entry = entries.get(run.callArgIndex);
                ArgValue value = entry.getValue();
                if (!await(run, step.path(), () -> value.resolveResumable(run.view(), run.reads))) {
                    return false;
                }
                run.callArgs.put(entry.getKey(), FlowInterpreter.take(run));
            }
        } catch (RuntimeException e) {
            if (e instanceof ReadUnavailableException) {
                failClosed(run, step.path(), e);
                return false;
            }
            consume(run, frame);
            return failure(run, step.path(), "call '" + step.flowId() + "' could not evaluate its arguments: "
                    + reasonOf(e), true);
        }
        Map<String, Object> args = java.util.Collections.unmodifiableMap(run.callArgs);
        consume(run, frame);
        emit(run, step.path(), TraceEvent.Type.CALLED, step.flowId());
        if (!step.flow().steps().isEmpty()) {
            run.calls++;
            push(run, Frame.call(step.flow().steps(), args));
        }
        return true;
    }

    final void enter(FlowRun run, Frame frame, List<Step> steps) {
        if (!steps.isEmpty()) {
            push(run, Frame.block(steps, frame.args));
        }
    }

    // --- conditions -----------------------------------------------------------------

    /** A condition that cannot be evaluated counts as false: a broken check must never grant. */
    final boolean holds(FlowRun run, StepPath path) {
        try {
            return Boolean.TRUE.equals(FlowInterpreter.take(run));
        } catch (RuntimeException e) {
            if (e instanceof ReadUnavailableException) {
                failClosed(run, path, e);
                return false;
            }
            reporter.failure(run.rule(), path, "condition failed: " + reasonOf(e));
            emit(run, path, TraceEvent.Type.STEP_FAILED, "condition failed: " + reasonOf(e));
            return false;
        }
    }

    final void failClosed(FlowRun run, StepPath path, RuntimeException failure) {
        run.view().facet(Cancellation.FACET).ifPresent(Cancellation::request);
        reporter.failure(run.rule(), path, "read unavailable: " + reasonOf(failure));
        finish(run, failure instanceof ReadRetiredException ? RunOutcome.DROPPED : RunOutcome.ABORTED,
                "read unavailable");
    }
}
