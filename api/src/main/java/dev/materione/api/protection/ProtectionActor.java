package dev.materione.api.protection;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Who is changing the world: an online player, an offline player (known by id only), or no one (the world acting by
 * itself, a rule that says {@code as_player: false}). Immutable and safe to pass between threads: an online actor holds the
 * live {@link Player}, which a provider may use only on the thread that owns that player, so the service hands a provider
 * an {@link #asOffline offline} actor whenever the asking thread does not own the player.
 */
public final class ProtectionActor {

    private static final ProtectionActor NONE = new ProtectionActor(null, null);

    private final Player online;
    private final OfflinePlayer offline;

    private ProtectionActor(Player online, OfflinePlayer offline) {
        this.online = online;
        this.offline = offline;
    }

    /** No one: the question is about the place alone. */
    public static ProtectionActor none() {
        return NONE;
    }

    /** An online player. */
    public static ProtectionActor of(Player player) {
        Objects.requireNonNull(player, "player");
        return new ProtectionActor(player, player);
    }

    /** A player known by their offline record (a connected player is better asked as {@link #of(Player)}). */
    public static ProtectionActor of(OfflinePlayer player) {
        Objects.requireNonNull(player, "player");
        return player instanceof Player online ? of(online) : new ProtectionActor(null, player);
    }

    /** Whether there is no actor. */
    public boolean isNone() {
        return offline == null;
    }

    /** The live player, when the actor is online and the asking thread may use them. */
    public Optional<Player> online() {
        return Optional.ofNullable(online);
    }

    /** The offline record of the actor (present for any actor), or empty for none. */
    public Optional<OfflinePlayer> offline() {
        return Optional.ofNullable(offline);
    }

    /** The actor's id, or empty for none. */
    public Optional<UUID> id() {
        return offline == null ? Optional.empty() : Optional.of(offline.getUniqueId());
    }

    /**
     * This actor as the offline player {@code record} stands for: the form a provider is given across a region boundary,
     * with no live {@code Player} to touch. The record must be a plain offline player, not an online one.
     */
    public ProtectionActor asOffline(OfflinePlayer record) {
        return offline == null || online == null ? this : new ProtectionActor(null, Objects.requireNonNull(record, "record"));
    }

    @Override
    public String toString() {
        return offline == null ? "no actor" : (online != null ? "player " : "offline player ") + offline.getUniqueId();
    }
}
