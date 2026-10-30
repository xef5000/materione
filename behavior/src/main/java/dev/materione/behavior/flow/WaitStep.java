package dev.materione.behavior.flow;

/**
 * {@code wait: <ticks>} or {@code wait: {ticks}}: parks the run and resumes it after the delay on
 * the owning player's thread. It never blocks a thread. A run is owned by the player, not by a
 * window, so it resumes even if the GUI that started it has closed (logic.md §5.2).
 *
 * @param path where the step was written
 * @param ticks how long to park; 0 yields until the next tick
 */
public record WaitStep(StepPath path, long ticks) implements Step {

    /** The longest wait an author may ask for: 24 hours of ticks. */
    public static final long MAX_TICKS = 24L * 60 * 60 * 20;

    @Override
    public String kind() {
        return "wait";
    }
}
