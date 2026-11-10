package dev.materione.plugin.escrow.runtime;

/**
 * What an exit's owner (#248's consume action, or any later hand-off) is told as the exit moves through the tier-2
 * steps and the tier-3 barrier (gui-input-durability.md §5.4). Every method has a default that does nothing, so the
 * trash, which has no effect, implements none of them. All of them run on the player's own thread except
 * {@link #abandoned} with {@link Abandoned#SHUTDOWN}, which runs on the disabling thread.
 *
 * <p>The order a caller can rely on: exactly one of {@link #refused} (the decision was not recorded; the stack went
 * back) or {@link #decided} (it was) happens first; after {@link #decided}, exactly one of {@link #acknowledged} or
 * {@link #abandoned} may follow, and neither follows a {@link #refused}.
 */
public interface ExitCallbacks {

    /** Why an exit's acknowledgment will not come in this session. */
    enum Abandoned {
        /** The player left before a snapshot with the exit was proven. Recovery applies the decision (K14). */
        PLAYER_LEFT,
        /** No snapshot was proven within the give-up time (the disk may not be saving). */
        TIMED_OUT,
        /** The plugin is stopping. */
        SHUTDOWN
    }

    /**
     * Whether an effect waits for {@link #acknowledged()}: only such an exit makes the barrier request the platform's
     * save (D8's exception admits it for no other); one with no effect (the trash) is acknowledged by the server's own
     * next save, found by polling. Defaults to false; an implementation that runs an effect in {@link #acknowledged()}
     * returns true (forgetting only delays the release until the server's own save: it is never early).
     */
    default boolean carriesEffect() {
        return false;
    }

    /**
     * The INTENT could not be recorded, so nothing was decided: the stack went back to the player (the inventory, or
     * OWED) and the transfer is refused (I7).
     */
    default void refused(Tier1.Reason reason) {}

    /**
     * The decision is durable (step 2 done). {@code applied} says whether step 3 ran live: the stack is gone and the
     * player sees the deletion. When it is false (the player left, or recovery got there first) the decision still
     * holds and recovery finishes it. Not an acknowledgment: the exit is still exposed to G3's rollback case.
     */
    default void decided(boolean applied) {}

    /**
     * The barrier proved a snapshot with the exit is on disk (the read-back found a {@code gen} at or above the exit's):
     * <b>an effect that waited for the acknowledgment runs now</b>, at most once. Never called for an exit whose
     * decision was not durable, or whose snapshot was not proven.
     */
    default void acknowledged() {}

    /** The acknowledgment will not come in this session: an effect that waited must not run. */
    default void abandoned(Abandoned why) {}

    /** An exit with no effect: it ignores every callback. */
    ExitCallbacks NONE = new ExitCallbacks() {};
}
