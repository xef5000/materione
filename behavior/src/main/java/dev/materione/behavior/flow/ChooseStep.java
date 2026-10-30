package dev.materione.behavior.flow;

import java.util.List;

/**
 * {@code choose: [{if, then}, ...], else: [steps]}: runs the first branch whose condition holds and
 * none of the others. It replaces long {@code if}/{@code else} chains, which is what makes priorities
 * readable. The conditions are evaluated lazily, in order, so a branch after the winner is never
 * tested.
 *
 * @param path where the step was written
 * @param cases the branches in authored order
 * @param otherwise the steps run when no branch holds, under {@code <path>.else}
 */
public record ChooseStep(StepPath path, List<Case> cases, List<Step> otherwise) implements Step {

    public ChooseStep {
        cases = List.copyOf(cases);
        otherwise = List.copyOf(otherwise);
    }

    @Override
    public String kind() {
        return "choose";
    }

    /**
     * One branch of a {@code choose}.
     *
     * @param path the case, {@code <step path>.choose.<i>}
     * @param condition when it holds
     * @param then what runs, under {@code <case path>.then}
     */
    public record Case(StepPath path, Condition condition, List<Step> then) {
        public Case {
            then = List.copyOf(then);
        }
    }
}
