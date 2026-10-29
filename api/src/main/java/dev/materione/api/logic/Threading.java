package dev.materione.api.logic;

/**
 * The thread an {@link ActionType} must execute on (logic.md §4.1), replacing the old boolean
 * "sync-safe". The action declares it; the engine dispatches through the scheduling gateway
 * (plugin.md §2), so no action ever picks a scheduler itself.
 */
public enum Threading {

    /**
     * Touches the acting player's own state (messages, sounds, effects, inventory). Runs on the
     * thread that owns the player: the region thread on Folia, the main thread on Paper. A step
     * that arrives on that thread already (the normal case: the event belongs to the player) runs
     * inline.
     */
    PLAYER_REGION,

    /**
     * May touch any region, the console or server-wide state (commands, broadcasts, transfers).
     * Runs on the global region thread (the main thread on Paper), through the gateway, never
     * inline from another region.
     */
    GLOBAL,

    /**
     * Pure bookkeeping on the run's own data (cancelling the event, writing a local variable). Safe
     * on any thread, so it runs inline wherever the run currently is. An action that reads or
     * mutates anything shared must not declare this.
     */
    ANY
}
