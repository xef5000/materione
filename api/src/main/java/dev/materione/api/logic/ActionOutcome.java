package dev.materione.api.logic;

import java.util.Objects;

/**
 * What an {@link ActionType#execute} reports (logic.md §4.1): it worked, or it failed with a
 * reason. A failure feeds the {@code require} step and the run trace; it never aborts a flow on
 * its own. An exception thrown out of {@code execute} is treated as a failure too, and also emits
 * a rate-limited diagnostic, so an action should return {@link #failed} for an expected refusal
 * ("not enough money", "no open gui") and reserve exceptions for bugs.
 */
public sealed interface ActionOutcome permits ActionOutcome.Ok, ActionOutcome.Failed {

    /** The action did what it was asked. */
    ActionOutcome OK = new Ok();

    /** The action could not do what it was asked; {@code reason} is shown in traces and diagnostics. */
    static ActionOutcome failed(String reason) {
        return new Failed(reason);
    }

    /** True for {@link #OK}. */
    default boolean isOk() {
        return this instanceof Ok;
    }

    /** The successful outcome. */
    record Ok() implements ActionOutcome {}

    /**
     * The failed outcome.
     *
     * @param reason a short human sentence; never blank
     */
    record Failed(String reason) implements ActionOutcome {
        public Failed {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("a failed outcome must say why");
            }
        }
    }
}
