package dev.materione.plugin.commands;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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

    /**
     * Resolves a name to the exact name of a player who is online or has joined this server before (case-insensitive),
     * or empty: what an {@code offline: true} argument accepts (logic.md §40). Never asks Mojang.
     */
    default Optional<String> known(String name) {
        return online(name);
    }

    /** The names an {@code offline: true} argument completes: everyone online, then players who joined before. */
    default Collection<String> knownNames() {
        return names();
    }

    /** The server's online players, and its user cache for the players who joined before. */
    static PlayerNames server() {
        return new PlayerNames() {
            private static final long REFRESH_MS = 30_000;
            private volatile long listedAt;
            private volatile List<String> listed = List.of();

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

            @Override
            public Optional<String> known(String name) {
                Optional<String> here = online(name);
                if (here.isPresent()) {
                    return here;
                }
                // the user cache only: getOfflinePlayer(name) would ask the profile service on this thread for an unknown name
                OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
                return cached != null && cached.hasPlayedBefore() && cached.getName() != null
                        ? Optional.of(cached.getName()) : Optional.empty();
            }

            @Override
            public Collection<String> knownNames() {
                long now = System.currentTimeMillis();
                if (now - listedAt > REFRESH_MS) {
                    // the player data folder is read at most twice a minute, however often the key is pressed
                    var all = new java.util.LinkedHashSet<String>();
                    for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                        if (player.getName() != null) {
                            all.add(player.getName());
                        }
                    }
                    listed = List.copyOf(all);
                    listedAt = now;
                }
                var out = new java.util.LinkedHashSet<>(names());
                out.addAll(listed);
                return out;
            }
        };
    }
}
