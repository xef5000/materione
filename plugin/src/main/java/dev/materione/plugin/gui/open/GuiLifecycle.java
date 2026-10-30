package dev.materione.plugin.gui.open;

import dev.materione.plugin.gui.GuiLookup;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.event.GuiEvents;
import dev.materione.plugin.gui.session.CloseReason;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.gui.session.GuiSessions;
import dev.materione.plugin.gui.window.WindowHost;

/**
 * How a session ends (logic.md §6.2). Every end goes through here, so {@code gui.close} fires once,
 * with the right reason, and the back stack is kept or dropped by the reason's own rule
 * ({@link CloseReason#keepsHistory()}): a flow closing the window, the player closing it, the player
 * leaving, the GUI going away. The replacement of one GUI by another is the opener's end, not this
 * class's.
 *
 * <p>Idempotent by construction: the first call to end a session wins, so a close that races another
 * close, a replace or a quit fires its trigger exactly once.
 */
public final class GuiLifecycle {

    private final GuiLookup guis;
    private final GuiSessions sessions;
    private final GuiEvents events;
    private final WindowHost windows;

    public GuiLifecycle(GuiLookup guis, GuiSessions sessions, GuiEvents events, WindowHost windows) {
        this.guis = guis;
        this.sessions = sessions;
        this.events = events;
        this.windows = windows;
    }

    /**
     * A flow closes the window ({@code gui.close}): ends the session with {@code action}, fires
     * {@code gui.close}, then closes the window.
     *
     * @return false when the session had already ended (nothing to close)
     */
    public boolean closeByAction(Viewer viewer, GuiSession session) {
        if (!end(viewer, session, CloseReason.ACTION)) {
            return false;
        }
        windows.close(viewer);
        return true;
    }

    /**
     * The window closed by itself (the player, death, a teleport, a disconnect): ends the session and
     * fires {@code gui.close}. A close that is not this session's current window is ignored.
     *
     * @param reason {@code player}, or {@code quit} when the cause was the disconnect
     */
    public void windowClosed(Viewer viewer, GuiSession session, CloseReason reason) {
        end(viewer, session, reason);
    }

    /** The player left: ends their session (if the window close did not already) and forgets their back stack. */
    public void playerQuit(Viewer viewer) {
        sessions.current(viewer.id()).ifPresent(session -> end(viewer, session, CloseReason.QUIT));
        sessions.forget(viewer.id());
    }

    /** The session's GUI stopped being live: ends it quietly (its rules are gone with it). */
    public void removed(GuiSession session) {
        sessions.end(session, CloseReason.REMOVED);
    }

    private boolean end(Viewer viewer, GuiSession session, CloseReason reason) {
        if (!sessions.end(session, reason)) {
            return false;
        }
        guis.find(session.gui()).ifPresent(gui -> events.closed(viewer, session, gui, reason));
        return true;
    }
}
