package dev.materione.core.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Executes bootstrap phases and shutdown steps with fail-soft semantics
 * (plugin architecture §1.1):
 *
 * <ul>
 *   <li>{@code run} never throws — every phase failure is captured, degraded
 *       (or stops the boot when the policy says DISABLE), and reported;</li>
 *   <li>timing is recorded per phase;</li>
 *   <li>{@code shutdown} runs steps in reverse order, each isolated, under a
 *       hard deadline — an unresponsive step is abandoned with a warning,
 *       never blocking server stop.</li>
 * </ul>
 */
public final class BootstrapPhaseRunner {

    private final Consumer<String> logger;

    public BootstrapPhaseRunner(Consumer<String> logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Result of one phase. {@code error} is the throwable's toString, if any. */
    public record PhaseResult(String name, boolean ok, long durationMs, String error) {}

    /** Result of a full bootstrap run. */
    public record RunResult(List<PhaseResult> phases, boolean disabled, int failures) {
        public RunResult {
            phases = List.copyOf(phases);
        }
    }

    public RunResult run(List<BootstrapPhase> phases) {
        Objects.requireNonNull(phases, "phases");
        List<PhaseResult> results = new ArrayList<>(phases.size());
        boolean disabled = false;
        int failures = 0;
        for (BootstrapPhase phase : phases) {
            long start = System.nanoTime();
            String error = null;
            try {
                phase.action().run();
            } catch (Throwable t) {
                failures++;
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
                logger.accept("phase '" + phase.name() + "' failed (" + error + ")");
                if (phase.policy() == FailurePolicy.DISABLE) {
                    disabled = true;
                }
            }
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            results.add(new PhaseResult(phase.name(), error == null, durationMs, error));
            if (disabled) {
                break;
            }
        }
        return new RunResult(results, disabled, failures);
    }

    /**
     * Runs shutdown steps in reverse order, each isolated, under a hard
     * deadline. Never throws.
     */
    public void shutdownReverse(List<String> stepNames, List<Runnable> steps, java.time.Duration hardTimeout) {
        Objects.requireNonNull(steps, "steps");
        long deadline = System.nanoTime() + hardTimeout.toNanos();
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (System.nanoTime() > deadline) {
                logger.accept("shutdown deadline exceeded; abandoning " + (i + 1) + " remaining step(s)");
                return;
            }
            String name = stepNames.get(i);
            try {
                steps.get(i).run();
            } catch (Throwable t) {
                logger.accept("shutdown step '" + name + "' failed ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage() + "); continuing");
            }
        }
    }
}
