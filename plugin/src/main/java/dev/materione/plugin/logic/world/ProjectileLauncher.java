package dev.materione.plugin.logic.world;

import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

/**
 * The platform's projectile launch (logic.md §41): a projectile of an entity type leaves a shooter with a velocity, through
 * the server's own launch path (a {@code ProjectileLaunchEvent}, so a protection plugin may veto it), and it carries the
 * id of the Materione item the launching run was about, so {@code entity.hit} can find that item's rules again when the
 * projectile lands ({@link ProjectileTags}).
 */
public interface ProjectileLauncher {

    /** The entity type named {@code key} (vanilla key, no namespace needed) when it can be launched, else empty. */
    Optional<EntityType> projectile(String key);

    /**
     * Launches one projectile.
     *
     * @param itemId the Materione identity of the item the run is about, or empty for none
     * @return the projectile, or empty when the launch was vetoed or the server could not make it
     */
    Optional<Entity> launch(ProjectileSource shooter, EntityType type, Vector velocity, Optional<String> itemId);

    /** The server's launcher: {@code launchProjectile} with the tag written to the projectile's persistent data. */
    static ProjectileLauncher server() {
        return new ProjectileLauncher() {
            @Override
            public Optional<EntityType> projectile(String key) {
                String name = key.startsWith("minecraft:") ? key.substring(10) : key;
                EntityType type;
                try {
                    type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(name));
                } catch (IllegalArgumentException malformed) {
                    return Optional.empty();
                }
                Class<?> entity = type == null ? null : type.getEntityClass();
                return entity != null && Projectile.class.isAssignableFrom(entity) ? Optional.of(type) : Optional.empty();
            }

            @Override
            @SuppressWarnings("unchecked")
            public Optional<Entity> launch(
                    ProjectileSource shooter, EntityType type, Vector velocity, Optional<String> itemId) {
                Projectile projectile = shooter.launchProjectile(
                        (Class<? extends Projectile>) type.getEntityClass(), velocity);
                if (projectile == null) {
                    return Optional.empty();
                }
                itemId.ifPresent(id -> ProjectileTags.tag(projectile, id));
                return Optional.of(projectile);
            }
        };
    }
}
