package dev.materione.plugin.logic.action;

import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicContext;
import dev.materione.behavior.logic.MapLogicContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * A recording stand-in for a {@link Player} (no server). Every call an action makes is appended to
 * {@link #calls} as a readable line, and the few questions an action asks back (experience, level,
 * whether a teleport or a command is accepted) are answered from plain fields. Stacks are never
 * created here, because a real {@link ItemStack} needs a server; the inventory is always empty.
 */
final class PlayerFake {

    final List<String> calls = new ArrayList<>();
    int totalExperience;
    int level;
    boolean teleportAccepted = true;
    boolean commandExists = true;
    /** The players of the player's world, answered by {@code getWorld().getPlayers()}. */
    final List<Player> worldPlayers = new ArrayList<>();

    private final World world = proxy(World.class, (method, args) -> switch (method.getName()) {
        case "getPlayers" -> List.copyOf(worldPlayers);
        default -> throw new AssertionError("unexpected world call: " + method.getName());
    });

    private final PlayerInventory inventory = proxy(PlayerInventory.class, (method, args) -> switch (method.getName()) {
        case "getContents" -> new ItemStack[41];
        default -> throw new AssertionError("unexpected inventory call: " + method.getName());
    });

    /** The player itself. */
    final Player player = proxy(Player.class, this::answer);

    private Object answer(Method method, Object[] args) {
        return switch (method.getName()) {
            case "sendMessage" -> record("message", args[0]);
            case "sendActionBar" -> record("actionbar", args[0]);
            case "showTitle" -> {
                Title title = (Title) args[0];
                calls.add("title:" + plain(title.title()) + "|" + plain(title.subtitle()) + "|"
                        + millisToTicks(title.times().fadeIn().toMillis()) + ","
                        + millisToTicks(title.times().stay().toMillis()) + ","
                        + millisToTicks(title.times().fadeOut().toMillis()));
                yield null;
            }
            case "playSound" -> {
                calls.add("sound:" + args[1] + ":" + args[2] + ":" + args[3]);
                yield null;
            }
            case "stopSound" -> {
                calls.add("stop:" + args[0] + (args.length > 1 ? ":" + args[1] : ""));
                yield null;
            }
            case "stopAllSounds" -> {
                calls.add("stop:all");
                yield null;
            }
            case "chat" -> {
                calls.add("chat:" + args[0]);
                yield null;
            }
            case "performCommand" -> {
                calls.add("command:" + args[0]);
                yield commandExists;
            }
            case "teleport" -> {
                Location to = (Location) args[0];
                calls.add("teleport:" + to.getX() + "," + to.getY() + "," + to.getZ()
                        + " yaw=" + to.getYaw() + " pitch=" + to.getPitch());
                yield teleportAccepted;
            }
            case "getLocation" -> new Location(world, 1, 2, 3, 90f, 10f);
            case "getWorld" -> world;
            case "getInventory" -> inventory;
            case "getTotalExperience" -> totalExperience;
            case "setTotalExperience" -> {
                totalExperience = (Integer) args[0];
                calls.add("setTotalExp:" + totalExperience);
                yield null;
            }
            case "getLevel" -> level;
            case "giveExp" -> {
                totalExperience += (Integer) args[0];
                calls.add("giveExp:" + args[0]);
                yield null;
            }
            case "giveExpLevels" -> {
                level += (Integer) args[0];
                calls.add("giveExpLevels:" + args[0]);
                yield null;
            }
            default -> throw new AssertionError("unexpected player call: " + method.getName());
        };
    }

    private Object record(String kind, Object component) {
        calls.add(kind + ":" + (component instanceof Component text ? plain(text) : component));
        return null;
    }

    private static long millisToTicks(long millis) {
        return millis / 50;
    }

    static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** A context whose run holds this player and the placeholder {@code %server%} resolves to Lobby. */
    LogicContext context() {
        return new MapLogicContext(Map.of("player", Map.of("name", "Alex")))
                .with(Facets.PLAYER, player)
                .withPlaceholders(text -> text.replace("%server%", "Lobby"));
    }

    /** A context with no player, for the "event has no player" paths. */
    static LogicContext noPlayer() {
        return new MapLogicContext(Map.of());
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Answer answers) {
        InvocationHandler handler = (target, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> target == args[0];
                    case "hashCode" -> System.identityHashCode(target);
                    default -> type.getSimpleName() + " stub";
                };
            }
            return answers.answer(method, args);
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    /** The answers a proxy gives to one method. */
    @FunctionalInterface
    interface Answer {
        Object answer(Method method, Object[] args);
    }
}
