package dev.materione.behavior.run;

import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.ReadRetiredException;
import dev.materione.api.logic.ResolvedTarget;
import dev.materione.api.logic.TargetResolution;
import dev.materione.api.logic.TargetResolver;
import dev.materione.behavior.flow.ActionStep;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * Runs one action step that has a {@code target:} (logic.md §39): resolves it, then runs the action once on each
 * subject, on the thread that owns that subject. It is the whole of the engine's target addressing; a step with
 * no target never reaches it and pays nothing for it.
 *
 * <p><b>Order and threads.</b> The target is resolved first, through the platform's {@link TargetResolver}, on the
 * run's own thread (a nearby search reads the run's player's world, a name lookup reads nothing). The action then
 * runs once per subject, in order, each on a context that carries the subject and a read executor aimed at the
 * subject's region ({@link RunReadExecutor#aimedAt}), so an effect on an entity in another region is dispatched
 * there through the gateway (D8) and the run continues on its own thread when it completes. Expression and template
 * reads stay with the run's own state.
 *
 * <p><b>Failure.</b> A target that does not resolve is a failed action, with the resolver's reason: that is what a
 * surrounding {@code require} and the trace see. With several subjects every one is attempted; the step fails when
 * any did, and says how many. An action that throws stops the step, as it does without a target.
 */
final class TargetedAction {

    private TargetedAction() {}

    /**
     * @param step a step whose {@link ActionStep#target()} is not null
     * @param resolver the platform's resolver, or null where there is none (the step then fails)
     */
    static <P> CompletionStage<ActionOutcome> run(FlowRun run, ActionStep<P> step, TargetResolver resolver) {
        var target = step.target();
        run.targetNote = "";
        if (resolver == null) {
            return CompletableFuture.completedFuture(ActionOutcome.failed("targets are not available here"));
        }
        LogicContext view = run.view();
        ReadExecutor origin = run.reads.captureOrigin(); // on the run's own thread, before any subject is touched
        CompletionStage<TargetResolution> found;
        try {
            found = resolver.resolve(target.spec(), target.admits(), view, origin);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(ActionOutcome.failed(
                    "target " + target.spec().describe() + " could not be resolved: " + failure.getMessage()));
        }
        return found.thenCompose(resolution -> {
            if (resolution.targets().isEmpty()) {
                return CompletableFuture.completedFuture(
                        ActionOutcome.failed("target " + target.spec().describe() + ": " + resolution.reason()));
            }
            run.targetNote = "on " + resolution.targets().stream()
                    .map(ResolvedTarget::describe).collect(Collectors.joining(", "));
            return each(run, step, view, origin, resolution.targets(), 0, new ArrayList<>());
        });
    }

    private static <P> CompletionStage<ActionOutcome> each(FlowRun run, ActionStep<P> step, LogicContext view,
            ReadExecutor origin, List<ResolvedTarget> targets, int index, List<String> failures) {
        if (index == targets.size()) {
            return CompletableFuture.completedFuture(failures.isEmpty()
                    ? ActionOutcome.OK
                    : ActionOutcome.failed(failures.size() == 1 ? failures.get(0)
                            : failures.size() + " of " + targets.size() + " targets failed: " + failures.get(0)));
        }
        ResolvedTarget subject = targets.get(index);
        ReadExecutor reads = run.reads.aimedAt(subject.anchor(), origin);
        CompletionStage<ActionOutcome> one = step.type().executeResumable(
                step.params(), new TargetedContext(view, subject), reads);
        return one.handle((outcome, error) -> {
            if (error != null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                if (cause instanceof ReadRetiredException) {
                    return ActionOutcome.failed(subject.describe() + " left before the action ran");
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new java.util.concurrent.CompletionException(cause);
            }
            return outcome == null ? ActionOutcome.failed("the action reported no outcome") : outcome;
        }).thenCompose(outcome -> {
            if (outcome instanceof ActionOutcome.Failed failed) {
                failures.add(targets.size() > 1 ? subject.describe() + ": " + failed.reason() : failed.reason());
            }
            return each(run, step, view, origin, targets, index + 1, failures);
        });
    }
}
