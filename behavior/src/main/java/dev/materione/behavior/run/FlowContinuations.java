package dev.materione.behavior.run;

import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.Threading;
import dev.materione.behavior.flow.ActionStep;
import dev.materione.behavior.flow.StepPath;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Nonblocking suspension, owner handoff and evaluation continuations for the flow interpreter. */
abstract class FlowContinuations extends ControlSteps {
    final RunRegistry registry = new RunRegistry();

    FlowContinuations(RunScheduler scheduler, RunBudget budget, RunReporter reporter) {
        super(scheduler, budget, reporter);
    }

    abstract void advanceLoop(FlowRun run);

    final <P> boolean action(FlowRun run, Frame frame, ActionStep<P> action) {
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
            if (run.synchronousOnly.getAsBoolean()) {
                failClosed(run, action.path(), new ReadUnavailableException(
                        "action requires " + needs + " before a synchronous event returns"));
                return false;
            }
            run.hopped = true;
            return !park(run, action.path(), needs, 0, false);
        }
        boolean thrown = false;
        ActionOutcome outcome;
        try {
            if (!await(run, action.path(), () ->
                    action.type().executeResumable(action.params(), run.view(), run.reads))) {
                return false;
            }
            outcome = take(run);
            if (outcome == null) {
                outcome = ActionOutcome.failed("the action reported no outcome");
            }
        } catch (RuntimeException e) {
            if (e instanceof ReadUnavailableException) {
                failClosed(run, action.path(), e);
                return false;
            }
            thrown = true;
            outcome = ActionOutcome.failed(reasonOf(e));
        }
        consume(run, frame);
        if (outcome instanceof ActionOutcome.Failed failed) {
            return failure(run, action.path(), "action '" + action.id() + "' failed: " + failed.reason(), thrown);
        }
        emit(run, action.path(), TraceEvent.Type.STEP_OK, "");
        return true;
    }

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
                    () -> resume(run, path, token, thread), () -> dropped(run, token));
            if (run.parkToken == token && run.parked) {
                run.pending = handle;
            }
        } catch (RuntimeException e) {
            endPark(run);
            abort(run, run.rule().path(), "could not schedule the run: " + reasonOf(e));
        }
        return true;
    }

    private void resume(FlowRun run, StepPath path, long token, Threading thread) {
        if (!run.executing.compareAndSet(false, true)) {
            defer(run, thread, () -> resume(run, path, token, thread));
            return;
        }
        try {
            resumeOwned(run, path, token);
        } finally {
            run.executing.set(false);
            dispatchDeferred(run);
        }
    }

    private void resumeOwned(FlowRun run, StepPath path, long token) {
        if (run.finished.get() || run.parkToken != token || !run.parked) {
            return; // cancelled, or a duplicate
        }
        endPark(run);
        emit(run, path, TraceEvent.Type.RESUMED, "");
        advanceLoop(run);
    }

    final void defer(FlowRun run, Threading owner, Runnable task) {
        run.deferredResume.set(new FlowRun.DeferredResume(owner, task));
        if (!run.executing.get()) {
            dispatchDeferred(run);
        }
    }

    final void dispatchDeferred(FlowRun run) {
        FlowRun.DeferredResume deferred = run.deferredResume.getAndSet(null);
        if (deferred == null || run.finished.get()) {
            return;
        }
        try {
            scheduler.schedule(run.owner().anchor(), deferred.owner(), 0, deferred.task(),
                    () -> finish(run, RunOutcome.DROPPED, "the player is gone"));
        } catch (RuntimeException failure) {
            abort(run, run.rule().path(), "could not schedule the run: " + reasonOf(failure));
        }
    }

    @Override
    final boolean await(FlowRun run, StepPath path, Supplier<? extends CompletionStage<?>> operation) {
        if (run.evaluation == null) {
            try {
                run.evaluation = operation.get().toCompletableFuture();
            } catch (RuntimeException failure) {
                run.evaluation = CompletableFuture.failedFuture(failure);
            }
        }
        if (run.evaluation.isDone()) {
            return true;
        }
        if (run.synchronousOnly.getAsBoolean()) {
            failClosed(run, path, new ReadUnavailableException(
                    "asynchronous evaluation before a synchronous event returns"));
            return false;
        }
        long token;
        synchronized (run) {
            if (run.cancelled || run.finished.get()) {
                return false;
            }
            if (!registry.admitParked(run, budget.maxParkedPerPlayer())) {
                abort(run, path, "the player already has " + budget.maxParkedPerPlayer() + " parked runs");
                return false;
            }
            token = ++run.parkToken;
            run.timedPark = true;
            run.parked = true;
        }
        emit(run, path, TraceEvent.Type.SUSPENDED, "read");
        run.evaluation.whenComplete((value, error) -> {
            if (run.finished.get() || run.parkToken != token) {
                return;
            }
            try {
                if (scheduler.canRunInline(run.owner().anchor(), Threading.PLAYER_REGION)) {
                    resume(run, path, token, Threading.PLAYER_REGION);
                    return;
                }
                RunScheduler.Handle handle = scheduler.schedule(run.owner().anchor(), Threading.PLAYER_REGION, 0,
                        () -> resume(run, path, token, Threading.PLAYER_REGION), () -> dropped(run, token));
                if (run.parkToken == token && run.parked) {
                    run.pending = handle;
                    if (run.finished.get() || run.parkToken != token || !run.parked) {
                        handle.cancel();
                    }
                } else {
                    handle.cancel();
                }
            } catch (RuntimeException failure) {
                abort(run, path, "could not schedule the run: " + reasonOf(failure));
            }
        });
        return false;
    }

    @SuppressWarnings("unchecked")
    static <T> T take(FlowRun run) {
        try {
            return (T) run.evaluation.join();
        } catch (java.util.concurrent.CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw failure;
        } finally {
            run.evaluation = null;
        }
    }

    private void dropped(FlowRun run, long token) {
        if (run.parkToken == token && !run.finished.get()) {
            finish(run, RunOutcome.DROPPED, "the player is gone");
        }
    }

    final void endPark(FlowRun run) {
        run.parked = false;
        run.pending = RunScheduler.Handle.NONE;
        synchronized (run) {
            if (run.timedPark) {
                run.timedPark = false;
                registry.releaseParked(run);
            }
        }
    }
}
