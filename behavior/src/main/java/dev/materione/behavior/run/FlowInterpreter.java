package dev.materione.behavior.run;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.Cancellation;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.Threading;
import dev.materione.behavior.flow.ActionStep;
import dev.materione.behavior.flow.ArgValue;
import dev.materione.behavior.flow.CallStep;
import dev.materione.behavior.flow.ChooseStep;
import dev.materione.behavior.flow.Condition;
import dev.materione.behavior.flow.IfStep;
import dev.materione.behavior.flow.RequireStep;
import dev.materione.behavior.flow.Step;
import dev.materione.behavior.flow.StepPath;
import dev.materione.behavior.flow.StopStep;
import dev.materione.behavior.flow.WaitStep;
import dev.materione.behavior.rule.Rule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Executes a rule's flow as a resumable state machine (logic.md §5.2). A {@link FlowRun} is an
 * explicit stack of {@link Frame}s, not Java recursion, so:
 *
 * <ul>
 *   <li><b>It can suspend.</b> A {@code wait}, an action's {@code delay:} and a thread hop park the
 *       run and hand the rest of it to the {@link RunScheduler}; nothing blocks and nothing nests.
 *   <li><b>Its cost is bounded.</b> Every step visited counts against the {@link RunBudget}.
 *   <li><b>It is observable.</b> Every step boundary can emit a {@link TraceEvent}.
 *   <li><b>It can be cancelled.</b> {@link #cancel} drops a parked run's pending resume.
 * </ul>
 *
 * <p>Steps run in order and inline for as long as the thread allows. Before an action the
 * interpreter asks the scheduler whether the current thread suits the action's
 * {@link Threading}; if not, it parks and resumes on the right one, so a console command and a
 * player message in one flow each run where they must, in the order written. An action's failure
 * never stops a run, except inside a {@code require}.
 *
 * <p>The interpreter is shared by every rule and player and holds no per-run state of its own.
 */
public final class FlowInterpreter extends ControlSteps {

    private final RunRegistry registry = new RunRegistry();
    private final AtomicLong ids = new AtomicLong();
    private volatile TraceSink trace;

    public FlowInterpreter(RunScheduler scheduler, RunBudget budget, Consumer<Diagnostic> diagnostics) {
        this(scheduler, budget, new RunReporter(diagnostics));
    }

    public FlowInterpreter(RunScheduler scheduler, RunBudget budget, RunReporter reporter) {
        super(scheduler, budget, reporter);
    }

    /** Installs (or, with null, removes) the sink step-boundary events go to. */
    public void traceTo(TraceSink sink) {
        this.trace = sink;
    }

    /** The rate-limited diagnostics reporter, shared with the rule gate. */
    public RunReporter reporter() {
        return reporter;
    }

    /**
     * Starts a run and executes it until it ends or first parks. The returned run may already be
     * finished.
     *
     * @param context the run's context: the host's variable tree and facets (including the
     *        cancellation handle)
     */
    public FlowRun start(Rule rule, LogicContext context, RunOwner owner) {
        var run = new FlowRun(ids.incrementAndGet(), rule, context, owner);
        if (owner.cancelKey() != null) {
            registry.track(run); // reachable by cancel() even while it is running
        }
        if (registry.parked(owner.playerKey()) >= budget.maxParkedPerPlayer()) {
            abort(run, rule.path(), "the player already has " + budget.maxParkedPerPlayer()
                    + " parked runs; this one was refused");
            return run;
        }
        emit(run, rule.path(), TraceEvent.Type.RUN_STARTED, "");
        run.stack.push(Frame.block(rule.steps(), null));
        advance(run);
        return run;
    }

    /**
     * Cancels every live run whose owner carries {@code cancelKey} (a closed GUI session, logic.md
     * §5.2). A parked run's pending resume is dropped; a run executing right now ends at its next
     * step boundary.
     *
     * @return how many runs were cancelled
     */
    @Override
    public int cancel(Object cancelKey) {
        return cancelAll(registry.byCancelKey(cancelKey));
    }

    /** Cancels every live run of one player (the player left). */
    public int cancelPlayer(Object playerKey) {
        return cancelAll(registry.byPlayer(playerKey));
    }

    /** Runs {@code playerKey} has parked on a wait or delay right now. */
    public int parkedRuns(Object playerKey) {
        return registry.parked(playerKey);
    }

    /** Live runs the interpreter can still reach (parked, or carrying a cancellation key). */
    public int liveRuns() {
        return registry.trackedRuns();
    }

    private int cancelAll(List<FlowRun> runs) {
        int count = 0;
        for (FlowRun run : runs) {
            if (run.finished.get()) {
                continue;
            }
            run.cancelled = true;
            run.pending.cancel();
            if (run.parked) {
                finish(run, RunOutcome.CANCELLED, "cancelled while parked");
            }
            count++;
        }
        return count;
    }

    // --- the loop -------------------------------------------------------------------

    private void advance(FlowRun run) {
        while (true) {
            if (run.cancelled) {
                finish(run, RunOutcome.CANCELLED, "cancelled");
                return;
            }
            Frame frame = run.stack.peek();
            if (frame == null) {
                finish(run, RunOutcome.FINISHED, "");
                return;
            }
            if (frame.exhausted()) {
                pop(run);
                if (frame.kind == Frame.Kind.FAIL_ELSE) {
                    finish(run, RunOutcome.REQUIRE_FAILED, "the else steps ended");
                    return;
                }
                continue;
            }
            Step step = frame.steps.get(frame.index);
            if (run.armed != step) {
                if (++run.steps > budget.maxStepsPerRun()) {
                    abort(run, step.path(), "more than " + budget.maxStepsPerRun() + " steps in one run");
                    return;
                }
                run.armed = step;
                run.stage = 0;
            }
            if (!execute(run, frame, step)) {
                return;
            }
        }
    }

    private boolean execute(FlowRun run, Frame frame, Step step) {
        return switch (step) {
            case ActionStep<?> action -> action(run, frame, action);
            case IfStep branch -> branch(run, frame, branch);
            case RequireStep require -> require(run, frame, require);
            case ChooseStep choose -> choose(run, frame, choose);
            case WaitStep wait -> wait(run, frame, wait);
            case StopStep stop -> stop(run, frame, stop);
            case CallStep call -> call(run, frame, call);
        };
    }

    @Override
    final void consume(FlowRun run, Frame frame) {
        frame.index++;
        run.armed = null;
        run.stage = 0;
        run.hopped = false;
    }

    @Override
    final void pop(FlowRun run) {
        Frame popped = run.stack.pop();
        if (popped.kind == Frame.Kind.CALL) {
            run.calls--;
        }
    }

    @Override
    final void push(FlowRun run, Frame frame) {
        run.stack.push(frame);
    }

    // --- steps ----------------------------------------------------------------------

    private <P> boolean action(FlowRun run, Frame frame, ActionStep<P> action) {
        if (run.stage == 0) {
            run.stage = 1;
            if (action.chance() < 1 && ThreadLocalRandom.current().nextDouble() >= action.chance()) {
                emit(run, action.path(), TraceEvent.Type.STEP_SKIPPED, "chance " + action.chance());
                consume(run, frame);
                return true;
            }
            if (action.delayTicks() > 0) {
                run.stage = 2; // the delay is served when the run comes back
                Threading back = action.threading() == Threading.GLOBAL ? Threading.GLOBAL : Threading.PLAYER_REGION;
                return !park(run, action.path(), back, action.delayTicks(), true);
            }
            run.stage = 2;
        }
        Threading needs = action.threading();
        if (needs != Threading.ANY && !run.hopped && !scheduler.canRunInline(run.owner().anchor(), needs)) {
            run.hopped = true;
            return !park(run, action.path(), needs, 0, false);
        }
        consume(run, frame);
        boolean thrown = false;
        ActionOutcome outcome;
        try {
            outcome = action.execute(run.view());
            if (outcome == null) {
                outcome = ActionOutcome.failed("the action reported no outcome");
            }
        } catch (RuntimeException e) {
            thrown = true;
            outcome = ActionOutcome.failed(reasonOf(e));
        }
        if (outcome instanceof ActionOutcome.Failed failed) {
            return failure(run, action.path(), "action '" + action.id() + "' failed: " + failed.reason(), thrown);
        }
        emit(run, action.path(), TraceEvent.Type.STEP_OK, "");
        return true;
    }

    /**
     * A step failed. Inside a {@code require} that is control flow: the require runs its else and
     * ends the run, and the failure is only traced. Anywhere else nobody handles it, so it is reported
     * (rate-limited) and the chain continues; an exception is always reported, handled or not.
     */
    @Override
    final boolean failure(FlowRun run, StepPath path, String message, boolean thrown) {
        emit(run, path, TraceEvent.Type.STEP_FAILED, message);
        Frame guard = run.nearestRequire();
        if (thrown || guard == null) {
            reporter.failure(run.rule(), path, message);
        }
        return guard == null || failRequire(run, guard, message);
    }

    // --- suspension -----------------------------------------------------------------

    /**
     * Parks the run and schedules its resumption.
     *
     * @param path the step the run is parked at, for the trace
     * @param timed whether this is a wait or delay (counted against the per-player limit) rather
     *        than a hop to another thread
     * @return true when the run is no longer executing on this thread (parked, or aborted)
     */
    @Override
    final boolean park(FlowRun run, StepPath path, Threading thread, long ticks, boolean timed) {
        long token;
        synchronized (run) { // the accounting and the flag change together, or a cancel could leak a count
            if (timed) {
                if (!registry.admitParked(run, budget.maxParkedPerPlayer())) {
                    abort(run, path, "the player already has " + budget.maxParkedPerPlayer() + " parked runs");
                    return true;
                }
                run.timedPark = true;
            }
            token = ++run.parkToken;
            run.parked = true;
        }
        emit(run, path, TraceEvent.Type.SUSPENDED,
                timed ? ticks + " ticks" : thread.name().toLowerCase(java.util.Locale.ROOT) + " thread");
        try {
            RunScheduler.Handle handle = scheduler.schedule(run.owner().anchor(), thread, ticks,
                    () -> resume(run, path, token), () -> dropped(run, token));
            if (run.parkToken == token && run.parked) {
                run.pending = handle;
            }
        } catch (RuntimeException e) {
            endPark(run);
            abort(run, run.rule().path(), "could not schedule the run: " + reasonOf(e));
        }
        return true;
    }

    private void resume(FlowRun run, StepPath path, long token) {
        if (run.finished.get() || run.parkToken != token || !run.parked) {
            return; // cancelled, or a duplicate
        }
        endPark(run);
        emit(run, path, TraceEvent.Type.RESUMED, "");
        advance(run);
    }

    private void dropped(FlowRun run, long token) {
        if (run.parkToken == token && !run.finished.get()) {
            finish(run, RunOutcome.DROPPED, "the player is gone");
        }
    }

    private void endPark(FlowRun run) {
        run.parked = false;
        run.pending = RunScheduler.Handle.NONE;
        synchronized (run) {
            if (run.timedPark) {
                run.timedPark = false;
                registry.releaseParked(run);
            }
        }
    }

    // --- ending ---------------------------------------------------------------------

    @Override
    final void abort(FlowRun run, StepPath path, String reason) {
        if (run.finished.get()) {
            return;
        }
        reporter.aborted(run.rule(), path, reason);
        finish(run, RunOutcome.ABORTED, reason);
    }

    @Override
    final void finish(FlowRun run, RunOutcome outcome, String detail) {
        if (!run.finished.compareAndSet(false, true)) {
            return;
        }
        run.outcome = outcome;
        run.pending.cancel();
        endPark(run);
        if (run.tracked) {
            registry.untrack(run);
        }
        emit(run, run.rule().path(), TraceEvent.Type.RUN_ENDED, outcome + (detail.isEmpty() ? "" : ": " + detail));
    }

    // --- trace ----------------------------------------------------------------------

    @Override
    final void emit(FlowRun run, StepPath path, TraceEvent.Type type, String detail) {
        TraceSink sink = trace;
        if (sink == null) {
            return;
        }
        try {
            sink.accept(new TraceEvent(run.id(), run.rule().id(), path, type, detail));
        } catch (RuntimeException ignored) {
            // a broken sink must never break a run
        }
    }

}
