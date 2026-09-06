package dev.materione.platform.delivery;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which players hold a loaded resource pack, and therefore can render
 * custom glyphs (runtime-systems §6): a codepoint sent to a client whose
 * pack predates the glyph renders as the missing-glyph box, so text
 * resolution is gated on this state instead. Pure state — Bukkit wiring
 * lives in the plugin's DeliveryService; unit-testable without a server.
 *
 * <p>The states follow one pack send: {@link State#PENDING} from the send
 * until the client reports, {@link State#LOADED} once it activated the
 * pack, {@link State#FAILED} when it declined or the download failed.
 * A client can hold only one pack at a time, so every send (join, reload
 * resend, editor preview) moves its target back to PENDING — a stale
 * LOADED from an earlier pack generation must never outlive the send
 * that replaced it.
 */
public final class PackAcceptance {

    public enum State {PENDING, LOADED, FAILED}

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    /** A pack was sent to the player; their previous state is obsolete. */
    public void sent(UUID player) {
        states.put(player, State.PENDING);
    }

    /** The client activated the pack ({@code SUCCESSFULLY_LOADED}). */
    public void loaded(UUID player) {
        states.put(player, State.LOADED);
    }

    /** The client accepted but has not finished loading — still pending. */
    public void accepted(UUID player) {
        states.computeIfPresent(player, (id, state) -> state == State.FAILED ? State.FAILED : State.PENDING);
    }

    /** The client declined, discarded, or failed to download the pack. */
    public void failed(UUID player) {
        states.put(player, State.FAILED);
    }

    /** The player left; no state is kept for them. */
    public void forgot(UUID player) {
        states.remove(player);
    }

    /** True only when the player's current pack generation is loaded. */
    public boolean canRender(UUID player) {
        return states.get(player) == State.LOADED;
    }

    /** A read-only view for observability (doctor output, diagnostics). */
    public Map<UUID, State> snapshot() {
        return Map.copyOf(states);
    }

    public int count(State state) {
        return (int) states.values().stream().filter(s -> s == state).count();
    }
}
