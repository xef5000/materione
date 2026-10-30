package dev.materione.behavior.flow;

/**
 * {@code stop}: ends this run. With {@code cancel: true} it first asks for the underlying event to
 * be cancelled (honoured only while the run has not yet suspended: the event is over by then). With
 * {@code cancel_session_runs: true} it also cancels every other run that shares this run's
 * cancellation key, which is how a GUI flow opts into the stricter "closing the window ends its
 * pending waits" behaviour (logic.md §5.2).
 *
 * @param path where the step was written
 * @param cancel whether to cancel the underlying event
 * @param cancelSessionRuns whether to cancel the runs that share this run's cancellation key
 */
public record StopStep(StepPath path, boolean cancel, boolean cancelSessionRuns) implements Step {

    @Override
    public String kind() {
        return "stop";
    }
}
