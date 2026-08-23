package dev.materione.behavior.runtime;

import dev.materione.behavior.rule.ActionSpec;

/**
 * An action implementation. Executors live outside the engine (the platform
 * registers them), keeping the behavior module embeddable and Bukkit-free.
 *
 * <p>{@link #syncSafe()} declares threading: {@code true} = safe on the
 * thread the event arrived on (player-local effects); {@code false} =
 * must-defer — the engine routes execution through the deferred runner so
 * world-mutating work lands on a sane thread (plugin.md §2 gateway).
 */
public interface ActionExecutor {

    /** The action type key ({@code run_command}, {@code message}, …). */
    String type();

    boolean syncSafe();

    /**
     * Executes the action. Throwing is allowed: the engine degrades it to a
     * rate-limited diagnostic and continues the chain (runtime-systems §2.1).
     */
    void execute(ActionSpec action, BehaviorEvent event);
}
