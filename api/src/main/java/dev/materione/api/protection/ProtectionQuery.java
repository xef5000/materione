package dev.materione.api.protection;

import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * One question for the port: the actor, what they want to do, and where. {@code victim} is set for
 * {@link ProtectionAction#DAMAGE} only.
 */
public record ProtectionQuery(ProtectionAction action, ProtectionActor actor, Location location, Entity victim) {

    public ProtectionQuery {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(location.getWorld(), "location.world");
    }

    public static ProtectionQuery of(ProtectionAction action, ProtectionActor actor, Location location) {
        return new ProtectionQuery(action, actor, location, null);
    }

    public static ProtectionQuery damage(ProtectionActor attacker, Entity victim) {
        return new ProtectionQuery(ProtectionAction.DAMAGE, attacker, victim.getLocation(), victim);
    }
}
