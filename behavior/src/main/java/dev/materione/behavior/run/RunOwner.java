package dev.materione.behavior.run;

/**
 * Whose a run is (logic.md §5.2): runs belong to the player, not to the window that started them.
 *
 * @param playerKey the identity parked runs are counted under (the player's UUID text); a run
 *        with no player shares one anonymous key
 * @param anchor the thread-locality hint the {@link RunScheduler} routes by (the player entity),
 *        or null
 * @param cancelKey the key {@link FlowInterpreter#cancel} matches, or null for a run nothing cancels
 *        by key. A GUI session passes its own identity here, so closing the window can end the
 *        runs it started; an item or block rule passes none and its run outlives everything but
 *        its player.
 */
public record RunOwner(Object playerKey, Object anchor, Object cancelKey) {

    /** The key runs with no known player are counted under. */
    public static final Object NO_PLAYER = "-";

    public RunOwner {
        playerKey = playerKey == null ? NO_PLAYER : playerKey;
    }

    /** An owner with no cancellation key. */
    public static RunOwner of(Object playerKey, Object anchor) {
        return new RunOwner(playerKey, anchor, null);
    }
}
