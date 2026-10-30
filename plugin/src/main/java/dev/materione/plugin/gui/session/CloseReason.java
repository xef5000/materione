package dev.materione.plugin.gui.session;

import java.util.Locale;

/**
 * Why a GUI session ended (logic.md §6.2). The first four are the values a {@code gui.close} rule
 * filters on with {@code reason:}; {@link #REMOVED} is the runtime's own end for a GUI that stopped
 * being live, which has no rules left to fire.
 */
public enum CloseReason {

    /** The window was closed outside the plugin's control: Esc, the inventory key, death, a teleport. */
    PLAYER,
    /** A flow closed it with {@code gui.close}. */
    ACTION,
    /** Another GUI took the player's window ({@code gui.open}, {@code gui.back}, the command). */
    REPLACED,
    /** The player left the server. */
    QUIT,
    /** The GUI is no longer live (removed, or its last compile quarantined it). Fires no trigger. */
    REMOVED;

    /** The value a rule writes in {@code reason:}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether ending a session for this reason fires {@code gui.close}. */
    public boolean fires() {
        return this != REMOVED;
    }

    /**
     * Whether the player's back stack survives it. A window that was replaced keeps the stack (that is
     * how {@code gui.back} has somewhere to go); any other end drops it.
     */
    public boolean keepsHistory() {
        return this == REPLACED;
    }
}
