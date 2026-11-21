package dev.materione.plugin.logic.world;

import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

/**
 * The tag a launched projectile carries (logic.md §41): the Materione id of the item the {@code launch} step was about,
 * in the projectile's own persistent data, so it survives a chunk unload and a restart. {@code entity.hit} reads it to
 * run that item's rules for the hit, whichever item the shooter holds by then.
 */
public final class ProjectileTags {

    private static final NamespacedKey KEY = new NamespacedKey("materione", "launched_by");

    private ProjectileTags() {}

    /** Records {@code itemId} on {@code projectile}. */
    public static void tag(Entity projectile, String itemId) {
        projectile.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, itemId);
    }

    /** The item id {@code projectile} was launched about, or empty for an untagged projectile (a bow's arrow, a ghast's fireball). */
    public static Optional<String> itemOf(Entity projectile) {
        return Optional.ofNullable(projectile.getPersistentDataContainer().get(KEY, PersistentDataType.STRING))
                .filter(id -> !id.isBlank());
    }
}
