package dev.materione.api.logic.provider;

import java.util.UUID;

/**
 * Sends a player to another server (logic.md §4.5; the {@code connect} action). Two shapes ship:
 * the 1.20.5+ transfer packet, which needs a host configured in settings, and BungeeCord or
 * Velocity plugin messaging, which needs the proxy. A server with neither has no transfer
 * provider and {@code connect} reports itself unavailable.
 */
public interface ServerTransfer {

    /** The provider's display name, such as {@code transfer packet}. */
    String name();

    /** Whether this provider can answer right now (see {@link EconomyProvider#available()}). */
    default boolean available() {
        return true;
    }

    /**
     * Sends the player to {@code server}. For the packet provider {@code server} is the
     * configured target and the argument is informational; for a proxy it names the proxy server.
     * The player must be online; the call returns once the request is handed off, before the
     * client has connected.
     */
    ProviderResult send(UUID player, String server);
}
