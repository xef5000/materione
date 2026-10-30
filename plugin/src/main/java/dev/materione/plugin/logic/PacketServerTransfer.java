package dev.materione.plugin.logic;

import dev.materione.api.logic.provider.ProviderResult;
import dev.materione.api.logic.provider.ServerTransfer;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The default server transfer (logic.md §4.5): the 1.20.5+ transfer packet, sent to the host and
 * port configured in settings ({@code transfer_host}, {@code transfer_port}). The target is the
 * configured one, so the server name a {@code connect} step passes is informational here; a proxy
 * uses that name instead ({@code BungeeCordTransfer} in {@code integrations}).
 */
public final class PacketServerTransfer implements ServerTransfer {

    private final String host;
    private final int port;

    /**
     * @param host the configured target host (settings {@code transfer_host})
     * @param port the configured target port (settings {@code transfer_port})
     * @throws IllegalArgumentException when the host is blank or the port is out of range
     */
    public PacketServerTransfer(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("a transfer host is required");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("transfer_port must be 1..65535");
        }
        this.host = host;
        this.port = port;
    }

    @Override
    public String name() {
        return "transfer packet (" + host + ":" + port + ")";
    }

    @Override
    public ProviderResult send(UUID player, String server) {
        Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return ProviderResult.failed("player is offline");
        }
        online.transfer(host, port);
        return ProviderResult.OK;
    }
}
