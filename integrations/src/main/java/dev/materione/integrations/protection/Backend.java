package dev.materione.integrations.protection;

import dev.materione.api.protection.ProtectionAction;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * The logic of one adapter (protection.md section 9), by who asks. {@link BackendPort} routes a port question to the method
 * that matches the actor, so an adapter states in three places what its plugin can say to an online player, to a player known
 * by id, and to no one. The defaults abstain: an adapter implements what its plugin's API can answer.
 */
public interface Backend {

    /** An online player acts. {@code victim} is set for {@link ProtectionAction#DAMAGE} only. */
    Decision online(ProtectionAction action, Player player, Location location, Entity victim);

    /** A player known by id only acts (a verb run for someone who is not here, or in another region). */
    default Decision offline(ProtectionAction action, OfflinePlayer player, Location location, Entity victim) {
        return Decision.abstain();
    }

    /** No one acts: the world changes the place by itself. */
    default Decision nobody(ProtectionAction action, Location location, Entity victim) {
        return Decision.abstain();
    }
}
