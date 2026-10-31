package dev.materione.plugin.gui.open;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The online players an open argument of type {@code player} resolves against, and the names tab
 * completion offers. A seam, so the parse is unit-tested without a server.
 */
public interface PlayerNames {

    /** Nobody is online: for tests and for a host with no server. */
    PlayerNames NONE = new PlayerNames() {
        @Override
        public Optional<String> online(String name) {
            return Optional.empty();
        }

        @Override
        public Collection<String> names() {
            return List.of();
        }
    };

    /** Resolves a name to the online player's exact name (case-insensitive), or empty. */
    Optional<String> online(String name);

    /** The names of everyone online, for tab completion. */
    Collection<String> names();

    /** The server's online players. */
    static PlayerNames server() {
        return new PlayerNames() {
            @Override
            public Optional<String> online(String name) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(player -> player.equalsIgnoreCase(name))
                        .findFirst();
            }

            @Override
            public Collection<String> names() {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            }
        };
    }
}
