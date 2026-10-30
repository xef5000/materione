package dev.materione.integrations.proxy;

import dev.materione.api.logic.provider.ProviderResult;
import dev.materione.api.logic.provider.ServerTransfer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Sends a player to another server by the BungeeCord plugin channel ({@code Connect} subchannel),
 * which BungeeCord and Velocity both answer (logic.md §4.5). Velocity answers it when
 * {@code bungeecord-message-channel} is enabled in its configuration. No proxy plugin is needed on
 * the Materione side; the channel is the contract, so this adapter is chosen by setting
 * ({@code transfer_mode: bungeecord}), not by detecting a plugin.
 */
public final class BungeeCordTransfer implements ServerTransfer {

    /** The outgoing channel both proxies listen on. */
    public static final String CHANNEL = "BungeeCord";

    private final Plugin host;

    /**
     * Registers the outgoing channel once.
     *
     * @param host the plugin that sends on the channel
     */
    public BungeeCordTransfer(Plugin host) {
        this.host = Objects.requireNonNull(host, "host");
        if (!host.getServer().getMessenger().isOutgoingChannelRegistered(host, CHANNEL)) {
            host.getServer().getMessenger().registerOutgoingPluginChannel(host, CHANNEL);
        }
    }

    @Override
    public String name() {
        return "BungeeCord plugin channel";
    }

    @Override
    public ProviderResult send(UUID player, String server) {
        Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return ProviderResult.failed("player is offline");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF("Connect");
                out.writeUTF(server);
            }
            online.sendPluginMessage(host, CHANNEL, bytes.toByteArray());
            return ProviderResult.OK;
        } catch (IOException e) {
            return ProviderResult.failed("could not encode the connect request: " + e.getMessage());
        }
    }
}
