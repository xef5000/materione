package dev.materione.plugin.gui.input;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which players have a click being dispatched right now. Changing a player's window from
 * inside the click that is being handled is unsafe (the client and server disagree about the cursor and
 * the slots for a tick), so the window host asks here and defers its open or close to the next tick when
 * the player is mid-click. A flow resumed from a {@code wait}, a command, a tick and the API are never
 * mid-click and change the window at once.
 *
 * <p>A click is dispatched on its player's thread from start to finish, so the entry for a player is
 * only ever written and read there.
 */
public final class InputGuard {

    private final Set<UUID> dispatching = ConcurrentHashMap.newKeySet();

    /** True while {@code player}'s click is being handled. */
    public boolean dispatching(UUID player) {
        return dispatching.contains(player);
    }

    /** Runs {@code dispatch} with the player marked as mid-click, even if it throws. */
    public void during(UUID player, Runnable dispatch) {
        boolean outermost = dispatching.add(player);
        try {
            dispatch.run();
        } finally {
            if (outermost) {
                dispatching.remove(player);
            }
        }
    }
}
