package dev.materione.plugin.view;

import java.util.Locale;

/**
 * Why a viewer record ended (logic.md §6.2, views.md §3.5). The first four are the values a {@code gui.close} rule
 * (and, with dialogs, a {@code dialog.close} rule) filters on with {@code reason:}; {@link #REMOVED} is the
 * runtime's own end for a view that stopped being live, which has no rules left to fire.
 */
public enum CloseReason {

    /** The window was closed outside the plugin's control: Esc, the inventory key, death, a teleport. */
    PLAYER,
    /** A flow closed it with {@code view.close}. */
    ACTION,
    /** Another view took the player's window ({@code view.open}, {@code view.back}, the command). */
    REPLACED,
    /** The player left the server. */
    QUIT,
    /** The view is no longer live (removed, or its last compile quarantined it). Fires no trigger. */
    REMOVED;

    /** The value a rule writes in {@code reason:}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether ending a viewer record for this reason fires the kind's close trigger. */
    public boolean fires() {
        return this != REMOVED;
    }

    /**
     * Whether the player's view history survives it. A view that was replaced keeps the history (that is how
     * {@code view.back} has somewhere to go); any other end drops it.
     */
    public boolean keepsHistory() {
        return this == REPLACED;
    }
}
