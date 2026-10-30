package dev.materione.plugin.gui;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * The player a GUI is open for, as the GUI runtime sees them: an identity that is always there and the
 * Bukkit {@link Player} that is there on a server. The session, opener and router work on the
 * identity, so their lifecycle is unit-tested without a server; only the window host and the
 * run's player facet reach for {@link #player()}.
 *
 * @param id the player's UUID, the key of their session and history
 * @param name the player's name, for logs and the {@code player.name} variable
 * @param player the Bukkit player; null only in a server-less test
 */
public record Viewer(UUID id, String name, Player player) {

    public Viewer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }

    /** The viewer for an online player. */
    public static Viewer of(Player player) {
        return new Viewer(player.getUniqueId(), player.getName(), player);
    }
}
