package dev.materione.behavior.flow;

import dev.materione.api.logic.Quantity;

/**
 * {@code wait: <ticks>} or {@code wait: {ticks}}: parks the run and resumes it after the delay on
 * the owning player's thread. It never blocks a thread. A run is owned by the player, not by a
 * window, so it resumes even if the GUI that started it has closed (logic.md §5.2). The ticks take a
 * number or a template that renders to one (logic.md section 42, #470).
 *
 * @param path where the step was written
 * @param ticks how long to park, a whole number from 0 to {@link #MAX_TICKS}; 0 yields until the next tick
 */
public record WaitStep(StepPath path, Quantity ticks) implements Step {

    /** The longest wait an author may ask for: 24 hours of ticks. */
    public static final long MAX_TICKS = dev.materione.behavior.logic.StepKinds.MAX_TICKS;

    /** A literal wait of {@code ticks} ticks, already checked by the compiler. */
    public WaitStep(StepPath path, long ticks) {
        this(path, Quantity.constant(ticks, 0, MAX_TICKS, true));
    }

    @Override
    public String kind() {
        return "wait";
    }
}
