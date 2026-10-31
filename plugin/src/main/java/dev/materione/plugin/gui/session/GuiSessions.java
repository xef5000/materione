package dev.materione.plugin.gui.session;

import dev.materione.api.NamespacedId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every open GUI session, one per player at most (logic.md §6). The registry is the only place that
 * says "this player has this GUI open"; the window's holder points at the session, and a window whose
 * session is no longer the player's current one is stale and ignored.
 *
 * <p>Region-safe by construction: the state is a concurrent map keyed by player, each player's entry is
 * only ever driven from that player's thread, and the one cross-thread read, the live refresh walking
 * every session, takes a snapshot. A session is added by {@link #begin} and removed by {@link #end},
 * which are idempotent and race-free against each other.
 */
public final class GuiSessions {

    private final ConcurrentHashMap<UUID, PlayerGuis> players = new ConcurrentHashMap<>();

    /** The player's current session, if a Materione GUI is open for them. */
    public Optional<GuiSession> current(UUID player) {
        PlayerGuis entry = players.get(player);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.current);
    }

    /**
     * The GUI the player had open before the current one: the last session that stopped being current,
     * by being replaced or by closing. Empty when they have had none since they joined.
     */
    public Optional<NamespacedId> last(UUID player) {
        PlayerGuis entry = players.get(player);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.last);
    }

    /** The player's back stack (created empty on first use). */
    public SessionHistory history(UUID player) {
        return players.computeIfAbsent(player, ignored -> new PlayerGuis()).history;
    }

    /**
     * Makes {@code next} the player's current session.
     *
     * @return the session it displaced, if the player had one; the caller ends it
     */
    public Optional<GuiSession> begin(GuiSession next) {
        PlayerGuis entry = players.computeIfAbsent(next.player(), ignored -> new PlayerGuis());
        synchronized (entry) {
            GuiSession previous = entry.current;
            if (previous != null) {
                entry.last = previous.gui();
            }
            entry.current = next;
            return Optional.ofNullable(previous);
        }
    }

    /**
     * Ends {@code session} for {@code reason}: marks it ended, and removes it if it is still the
     * player's current session. A reason that does not keep history also clears the back stack.
     *
     * @return true when this call ended it; false when it had already ended (the caller then fires nothing)
     */
    public boolean end(GuiSession session, CloseReason reason) {
        boolean ended = session.end(reason);
        PlayerGuis entry = players.get(session.player());
        if (entry != null) {
            synchronized (entry) {
                if (entry.current == session) {
                    entry.current = null;
                    entry.last = session.gui();
                }
                if (ended && !reason.keepsHistory() && entry.current == null) {
                    entry.history.clear();
                }
            }
        }
        return ended;
    }

    /** Drops everything remembered about a player who left. */
    public void forget(UUID player) {
        players.remove(player);
    }

    /** The open sessions showing {@code gui}, as a snapshot safe to iterate while sessions end. */
    public List<GuiSession> showing(NamespacedId gui) {
        var out = new ArrayList<GuiSession>();
        for (PlayerGuis entry : players.values()) {
            GuiSession session = entry.current;
            if (session != null && session.gui().equals(gui)) {
                out.add(session);
            }
        }
        return out;
    }

    /** Every open session, as a snapshot. */
    public List<GuiSession> all() {
        var out = new ArrayList<GuiSession>();
        for (PlayerGuis entry : players.values()) {
            GuiSession session = entry.current;
            if (session != null) {
                out.add(session);
            }
        }
        return out;
    }

    /** How many players have a GUI open. */
    public int openCount() {
        return all().size();
    }

    private static final class PlayerGuis {
        private volatile GuiSession current;
        private volatile NamespacedId last;
        private final SessionHistory history = new SessionHistory();
    }
}
