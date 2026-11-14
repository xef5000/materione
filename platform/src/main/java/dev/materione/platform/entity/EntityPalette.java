package dev.materione.platform.entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Registry;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * The names the world-event triggers filter on (logic.md §37), read from the running server so a rule that
 * names an entity type or damage cause this server does not have is refused at load. Entity types come from
 * the server's registry (the one source on every supported version); damage causes are the Bukkit enum.
 * Both are lowercase, as a rule writes them ({@code zombie}, {@code entity_attack}).
 */
public final class EntityPalette {

    private EntityPalette() {}

    /**
     * Every vanilla entity type the server knows, by key without namespace, sorted. Empty when no server registry
     * is reachable (a unit test), in which case a trigger's {@code type} filter accepts any name.
     */
    public static List<String> entityTypes() {
        try {
            var out = new ArrayList<String>();
            for (var type : Registry.ENTITY_TYPE) {
                out.add(type.getKey().getKey());
            }
            return out.stream().sorted().toList();
        } catch (RuntimeException | LinkageError noServer) {
            return List.of();
        }
    }

    /** Every damage cause, lowercase, in the enum's order. */
    public static List<String> damageCauses() {
        return Arrays.stream(EntityDamageEvent.DamageCause.values())
                .map(cause -> cause.name().toLowerCase(java.util.Locale.ROOT)).toList();
    }

    /** The key of an entity's type without namespace ({@code zombie}); {@code unknown} when it has none. */
    public static String typeOf(Entity entity) {
        try {
            return entity.getType().getKey().getKey();
        } catch (RuntimeException noKey) {
            return "unknown";
        }
    }

    /** A damage cause as a rule writes it. */
    public static String causeOf(EntityDamageEvent.DamageCause cause) {
        return cause == null ? "custom" : cause.name().toLowerCase(java.util.Locale.ROOT);
    }
}
