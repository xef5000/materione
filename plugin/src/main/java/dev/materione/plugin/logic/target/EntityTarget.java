package dev.materione.plugin.logic.target;

import dev.materione.api.logic.ResolvedTarget;
import dev.materione.api.logic.TargetKind;
import dev.materione.platform.entity.EntityPalette;
import java.util.Objects;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * A resolved target on the server: the entity an action acts on. The engine routes the action's own-region work to
 * its owning thread through the {@link #anchor()}; the action reads it through {@code Subjects}.
 *
 * @param entity the player or entity; resolved on the thread that found it, owned by its own region
 */
public record EntityTarget(Entity entity) implements ResolvedTarget {

    public EntityTarget {
        Objects.requireNonNull(entity, "entity");
    }

    @Override
    public Object anchor() {
        return entity;
    }

    @Override
    public TargetKind kind() {
        return entity instanceof Player ? TargetKind.PLAYER : TargetKind.ENTITY;
    }

    @Override
    public String describe() {
        if (entity instanceof Player player) {
            return "player " + player.getName();
        }
        String id = entity.getUniqueId().toString();
        return EntityPalette.typeOf(entity) + " " + id.substring(0, 8);
    }
}
