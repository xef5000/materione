package dev.materione.plugin.gui.session;

import dev.materione.api.logic.variable.GuiSessionPort;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code %materione_gui_open%} and {@code %materione_gui_last%} (logic.md §3.3) answered from the live
 * sessions: the GUI the player has open, and the one they had before it.
 */
public final class GuiSessionsPort implements GuiSessionPort {

    private final GuiSessions sessions;

    public GuiSessionsPort(GuiSessions sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public Optional<String> openGui(UUID player) {
        return sessions.current(player).filter(GuiSession::isOpen).map(session -> session.gui().toString());
    }

    @Override
    public Optional<String> lastGui(UUID player) {
        return sessions.last(player).map(Object::toString);
    }
}
