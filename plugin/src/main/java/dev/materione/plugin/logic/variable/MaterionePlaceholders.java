package dev.materione.plugin.logic.variable;

import dev.materione.integrations.variables.VariablePlaceholders;
import java.util.Optional;
import java.util.UUID;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * The {@code %materione_…%} PlaceholderAPI expansion (logic.md §3.3). It is the only class that names
 * PlaceholderAPI, and it is installed only when PlaceholderAPI is enabled, so a server without it never
 * loads it: {@link #installIfPresent} checks first.
 */
public final class MaterionePlaceholders extends PlaceholderExpansion {

    private final VariablePlaceholders resolver;
    private final String version;

    MaterionePlaceholders(VariablePlaceholders resolver, String version) {
        this.resolver = resolver;
        this.version = version;
    }

    /**
     * Registers the expansion when PlaceholderAPI is enabled on this server.
     *
     * @return true when it was registered
     */
    public static boolean installIfPresent(VariablePlaceholders resolver, String version) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return false;
        }
        return new MaterionePlaceholders(resolver, version).register();
    }

    @Override
    public String getIdentifier() {
        return "materione";
    }

    @Override
    public String getAuthor() {
        return "Materione";
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        UUID viewer = player == null ? null : player.getUniqueId();
        return resolver.resolve(params, viewer, MaterionePlaceholders::playerByName);
    }

    /** A name to a player id: an online player first, then one the server has seen before. */
    private static Optional<UUID> playerByName(String name) {
        var online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        return Optional.ofNullable(Bukkit.getOfflinePlayerIfCached(name)).map(OfflinePlayer::getUniqueId);
    }
}
