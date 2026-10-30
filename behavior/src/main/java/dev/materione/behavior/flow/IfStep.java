package dev.materione.behavior.flow;

import java.util.List;

/**
 * {@code if: <condition>, then: [steps], else: [steps]}: a branch. Execution continues after the
 * step once the chosen branch ends. A condition that cannot be evaluated counts as false.
 *
 * @param path where the step was written
 * @param condition the compiled condition
 * @param then the steps run when it holds; their paths sit under {@code <path>.then}
 * @param otherwise the steps run when it does not; their paths sit under {@code <path>.else}
 */
public record IfStep(StepPath path, Condition condition, List<Step> then, List<Step> otherwise) implements Step {

    public IfStep {
        then = List.copyOf(then);
        otherwise = List.copyOf(otherwise);
    }

    @Override
    public String kind() {
        return "if";
    }
}
