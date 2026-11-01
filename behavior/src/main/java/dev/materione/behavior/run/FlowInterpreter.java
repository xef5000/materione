package dev.materione.behavior.run;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.Threading;
import dev.materione.behavior.flow.ActionStep;
import dev.materione.behavior.flow.CallStep;
import dev.materione.behavior.flow.ChooseStep;
import dev.materione.behavior.flow.IfStep;
import dev.materione.behavior.flow.RequireStep;
import dev.materione.behavior.flow.Step;
import dev.materione.behavior.flow.StepPath;
import dev.materione.behavior.flow.StopStep;
import dev.materione.behavior.flow.WaitStep;
import dev.materione.behavior.rule.Rule;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
public final class FlowInterpreter extends FlowContinuations {

    private final AtomicLong ids = new AtomicLong();
    private volatile TraceSink trace;
    private final Set<GateReads> gates = ConcurrentHashMap.newKeySet();

    /** A pending rule gate participates in the same cancellation lifecycle as a parked run. */
    public final class GateReads implements AutoCloseable {
        private final RunOwner owner;
        private final RunReadExecutor reads;
        private final BooleanSupplier synchronousOnly;
        private boolean parked;

        private GateReads(RunOwner owner, BooleanSupplier synchronousOnly) {
            this.owner = owner;
            this.synchronousOnly = synchronousOnly;
            this.reads = new RunReadExecutor(scheduler, owner.anchor(), synchronousOnly);
        }

        public RunReadExecutor executor() {
            return reads;
        }

        /** Charges a pending gate to the same budget as a suspended flow. */
        public synchronized boolean park() {
            if (!reads.active() || !registry.admitGate(owner.playerKey(), budget.maxParkedPerPlayer())) {
                return false;
            }
            parked = true;
            return true;
        }

        public synchronized void resume() {
            if (parked) {
                parked = false;
                registry.releaseGate(owner.playerKey());
            }
        }

        /** Registers before releasing the gate lock, so cancellation cannot miss the new run. */
        public FlowRun start(Rule rule, LogicContext context) {
            FlowRun run;
            synchronized (this) {
                if (!reads.active()) {
                    return null;
                }
                run = new FlowRun(ids.incrementAndGet(), rule, context, owner, scheduler, synchronousOnly);
                registry.track(run);
            }
            return startPrepared(run);
        }

        @Override public synchronized void close() {
            gates.remove(this);
            reads.close();
            resume();
        }
    }

    public GateReads gateReads(RunOwner owner, BooleanSupplier synchronousOnly) {
        var gate = new GateReads(owner, synchronousOnly);
        gates.add(gate);
        return gate;
    }

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
        return start(rule, context, owner, () -> false);
    }

    public FlowRun start(Rule rule, LogicContext context, RunOwner owner, BooleanSupplier synchronousOnly) {
        var run = new FlowRun(ids.incrementAndGet(), rule, context, owner, scheduler, synchronousOnly);
        if (owner.cancelKey() != null) {
            registry.track(run); // reachable by cancel() even while it is running
        }
        return startPrepared(run);
    }

    private FlowRun startPrepared(FlowRun run) {
        Rule rule = run.rule();
        RunOwner owner = run.owner();
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
        int count = 0;
        if (cancelKey != null) {
            for (GateReads gate : gates) {
                if (cancelKey.equals(gate.owner.cancelKey())) {
                    gate.close();
                    count++;
                }
            }
        }
        return count + cancelAll(registry.byCancelKey(cancelKey));
    }

    /** Cancels every live run of one player (the player left). */
    public int cancelPlayer(Object playerKey) {
        int count = 0;
        for (GateReads gate : gates) {
            if (java.util.Objects.equals(playerKey, gate.owner.playerKey())) {
                gate.close();
                count++;
            }
        }
        return count + cancelAll(registry.byPlayer(playerKey));
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
        if (!run.executing.compareAndSet(false, true)) {
            defer(run, Threading.PLAYER_REGION, () -> advance(run));
            return;
        }
        try {
            advanceLoop(run);
        } finally {
            run.executing.set(false);
            dispatchDeferred(run);
        }
    }

    @Override
    final void advanceLoop(FlowRun run) {
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
        run.evaluation = null;
        run.choiceIndex = 0;
        run.callArgs = null;
        run.callReads = null;
        run.choiceReads = null;
        run.callArgIndex = 0;
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
        run.reads.close();
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
