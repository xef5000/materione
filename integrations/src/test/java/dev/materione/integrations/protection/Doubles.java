package dev.materione.integrations.protection;

import dev.materione.api.protection.ProtectionActor;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/** Stand-ins for the adapters' tests: players, victims and a location, with no server and none of the protected plugins. */
final class Doubles {

    static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");

    private Doubles() {}

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, java.util.function.BiFunction<java.lang.reflect.Method, Object[], Object> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    default -> type.getSimpleName();
                };
            }
            return answers.apply(method, args);
        });
    }

    /** An online player; {@code permissions} are the nodes {@code hasPermission} grants. */
    static Player player(String... permissions) {
        var granted = java.util.Set.of(permissions);
        return proxy(Player.class, (method, args) -> switch (method.getName()) {
            case "getUniqueId" -> ALEX;
            case "hasPermission" -> granted.contains(args[0]);
            default -> throw new AssertionError(method.getName());
        });
    }

    static OfflinePlayer offlinePlayer() {
        return proxy(OfflinePlayer.class, (method, args) -> switch (method.getName()) {
            case "getUniqueId" -> ALEX;
            default -> throw new AssertionError(method.getName());
        });
    }

    static ProtectionActor online(String... permissions) {
        return ProtectionActor.of(player(permissions));
    }

    static ProtectionActor offline() {
        return ProtectionActor.of(offlinePlayer());
    }

    /** A location in a world; the adapters under test never read the world, only hand the location on. */
    static Location location() {
        var world = proxy(org.bukkit.World.class, (method, args) -> switch (method.getName()) {
            case "getName" -> "world";
            default -> throw new AssertionError(method.getName());
        });
        return new Location(world, 1, 64, 1);
    }

    static Entity victim() {
        var at = location();
        return proxy(Entity.class, (method, args) -> switch (method.getName()) {
            case "getLocation" -> at.clone();
            default -> throw new AssertionError(method.getName());
        });
    }
}
