package dev.materione.behavior.flow;

import java.util.List;

/**
 * {@code require: [steps], else: [steps]}: runs the steps in order until one <em>fails</em>. On a
 * failure it runs {@code else} and ends the whole run, so the steps after the failed one never
 * happen. This is the atomic-paywall shape ({@code take_money} then {@code give_item}) without a
 * separate {@code if} check.
 *
 * <p>A failure is an action returning {@code FAILED} (or throwing) anywhere in the require's
 * dynamic extent: in its own list, in a nested branch, or in a flow it {@code call}s.
 *
 * <p><b>Fail closed.</b> {@code blocked} is true when a step inside the body could not be loaded
 * (an unknown action, rejected parameters). The guard then cannot be trusted to protect what follows
 * it, so it fails every time: the {@code else} steps run and the run ends. Skipping the unloadable
 * step alone would turn a paywall into a gift.
 *
 * @param path where the step was written
 * @param steps the guarded steps; their paths sit under {@code <path>.require}
 * @param otherwise the steps run on failure, under {@code <path>.else}
 * @param blocked whether the body lost a step at load, so the guard fails closed
 */
public record RequireStep(StepPath path, List<Step> steps, List<Step> otherwise, boolean blocked) implements Step {

    public RequireStep {
        steps = List.copyOf(steps);
        otherwise = List.copyOf(otherwise);
    }

    @Override
    public String kind() {
        return "require";
    }
}
