package dev.materione.core.bootstrap;

import java.util.Objects;

/**
 * One named bootstrap step. Phases run strictly in list order; registration
 * of anything user-visible comes last (nothing is executable before the
 * system it touches is ready).
 */
public record BootstrapPhase(String name, FailurePolicy policy, Runnable action) {

    public BootstrapPhase {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(action, "action");
    }

    public static BootstrapPhase degrade(String name, Runnable action) {
        return new BootstrapPhase(name, FailurePolicy.DEGRADE, action);
    }

    public static BootstrapPhase disable(String name, Runnable action) {
        return new BootstrapPhase(name, FailurePolicy.DISABLE, action);
    }
}
