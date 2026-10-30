package dev.materione.plugin.logic;

import dev.materione.api.logic.provider.PermissionProvider;
import dev.materione.api.logic.provider.ProviderResult;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

/**
 * The default permission provider (logic.md §4.5): grants as Bukkit attachments owned by Materione.
 * It needs no permission plugin and lasts for the session only. {@link #revoke} removes the grant
 * this provider made; it does not take away a node a permission plugin gives, which only that
 * plugin can change. Attachments are created and removed on the player's own thread, which is where
 * the permission actions run.
 */
final class BukkitPermissions implements PermissionProvider, Listener {

    private final Plugin host;
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    /** @param host the plugin that owns the attachments; its plugin manager delivers quit events */
    BukkitPermissions(Plugin host) {
        this.host = Objects.requireNonNull(host, "host");
        host.getServer().getPluginManager().registerEvents(this, host);
    }

    @Override
    public String name() {
        return "Bukkit attachments (this session only)";
    }

    @Override
    public ProviderResult grant(UUID player, String node) {
        Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return ProviderResult.failed("player is offline");
        }
        attachments.computeIfAbsent(player, id -> online.addAttachment(host)).setPermission(node, true);
        return ProviderResult.OK;
    }

    @Override
    public ProviderResult revoke(UUID player, String node) {
        PermissionAttachment attachment = attachments.get(player);
        if (attachment != null) {
            attachment.unsetPermission(node);
        }
        return ProviderResult.OK;
    }

    /** A quitting player's attachment is dropped with the player; the map must not keep it. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        attachments.remove(event.getPlayer().getUniqueId());
    }
}
