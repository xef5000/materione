package dev.materione.plugin.command;

import dev.materione.api.NamespacedId;
import dev.materione.plugin.MaterionePlugin;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /materione dev place|remove|inspect|sweep} — the world-runtime
 * operator surface and the harness's placement driver (permission
 * {@code materione.dev}). Every subcommand is console-runnable: coordinates
 * are required unless a player sends the command, and all world access is
 * routed through the scheduling gateway so markers always land after the
 * mutation did.
 */
public final class DevCommands {

    private final MaterionePlugin plugin;

    public DevCommands(MaterionePlugin plugin) {
        this.plugin = plugin;
    }

    /** Handles {@code /materione dev …}; {@code args} starts at the subcommand. */
    public void handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("materione.dev")) {
            sender.sendMessage(Component.text("no permission (materione.dev)", NamedTextColor.RED));
            return;
        }
        String sub = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "place" -> place(sender, args);
            case "remove" -> remove(sender, args);
            case "inspect" -> inspect(sender, args);
            case "sweep" -> sweep(sender, args);
            default -> sender.sendMessage(Component.text(
                    "usage: /materione dev <place|remove|inspect|sweep> — "
                            + "place <block> [x y z [world]] · remove <x y z [world]> · "
                            + "inspect [x y z [world]] · sweep [--fix]",
                    NamedTextColor.RED));
        }
    }

    /** Tab completion for the {@code dev} branch. */
    public java.util.List<String> complete(String[] args) {
        if (args.length == 2) {
            return java.util.List.of("place", "remove", "inspect", "sweep").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return java.util.List.of();
    }

    private void place(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender, "/materione dev place <blockId> [x y z [world]]");
            return;
        }
        NamespacedId blockId = resolveBlock(args[2]);
        int[] coords;
        World world;
        try {
            if (args.length >= 6) {
                coords = coordsAt(args, 3);
                world = worldAt(sender, args, 6);
            } else {
                Player viewer = asPlayer(sender);
                org.bukkit.Location location = viewer.getLocation().toBlockLocation();
                coords = new int[] {location.getBlockX(), location.getBlockY(), location.getBlockZ()};
                world = viewer.getWorld();
            }
        } catch (IllegalArgumentException e) {
            usage(sender, e.getMessage());
            return;
        }
        World target = world;
        plugin.customBlocks().devPlace(target, coords[0], coords[1], coords[2], blockId, status -> {
            sender.sendMessage(Component.text("dev place: " + status,
                    status.startsWith("placed") ? NamedTextColor.GREEN : NamedTextColor.RED));
            plugin.getLogger().info("MATERIONE-DEV-PLACE ok=" + status.startsWith("placed")
                    + " " + status);
        });
    }

    private void remove(CommandSender sender, String[] args) {
        int[] coords;
        World world;
        try {
            if (args.length >= 5) {
                coords = coordsAt(args, 2);
                world = worldAt(sender, args, 5);
            } else {
                Player viewer = asPlayer(sender);
                org.bukkit.block.Block target = viewer.getTargetBlockExact(8);
                org.bukkit.Location location = (target == null
                        ? viewer.getLocation().toBlockLocation()
                        : target.getLocation());
                coords = new int[] {location.getBlockX(), location.getBlockY(), location.getBlockZ()};
                world = viewer.getWorld();
            }
        } catch (IllegalArgumentException e) {
            usage(sender, e.getMessage());
            return;
        }
        plugin.customBlocks().devRemove(world, coords[0], coords[1], coords[2], status -> {
            boolean ok = status.startsWith("removed");
            sender.sendMessage(Component.text("dev remove: " + status,
                    ok ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            plugin.getLogger().info("MATERIONE-DEV-REMOVE ok=" + ok + " " + status);
        });
    }

    private void inspect(CommandSender sender, String[] args) {
        int[] coords;
        World world;
        try {
            if (args.length >= 5) {
                coords = coordsAt(args, 2);
                world = worldAt(sender, args, 5);
            } else {
                Player viewer = asPlayer(sender);
                org.bukkit.block.Block target = viewer.getTargetBlockExact(8);
                org.bukkit.Location location = (target == null
                        ? viewer.getLocation().toBlockLocation()
                        : target.getLocation());
                coords = new int[] {location.getBlockX(), location.getBlockY(), location.getBlockZ()};
                world = viewer.getWorld();
            }
        } catch (IllegalArgumentException e) {
            usage(sender, e.getMessage());
            return;
        }
        plugin.customBlocks().devInspect(world, coords[0], coords[1], coords[2], status -> {
            boolean found = !status.startsWith("none");
            sender.sendMessage(Component.text("dev inspect: " + status,
                    found ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            plugin.getLogger().info("MATERIONE-DEV-INSPECT found=" + found + " " + status);
        });
    }

    private void sweep(CommandSender sender, String[] args) {
        boolean fix = args.length > 2 && "--fix".equalsIgnoreCase(args[2]);
        plugin.customBlocks().devSweep(fix, status -> {
            sender.sendMessage(Component.text("dev sweep: " + status, NamedTextColor.GREEN));
            plugin.getLogger().info("MATERIONE-DEV-SWEEP " + status);
        });
    }

    // --- argument helpers -------------------------------------------------------

    /** Bare ids resolve into the materione namespace. */
    private static NamespacedId resolveBlock(String raw) {
        return raw.contains(":") ? NamespacedId.parse(raw) : NamespacedId.of("materione", raw);
    }

    /** Three integers starting at {@code offset}. */
    private static int[] coordsAt(String[] args, int offset) {
        try {
            return new int[] {
                    Integer.parseInt(args[offset]),
                    Integer.parseInt(args[offset + 1]),
                    Integer.parseInt(args[offset + 2])};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("coordinates must be integers: x y z");
        }
    }

    /**
     * The world from an optional trailing argument; console senders must name
     * one explicitly.
     */
    private static World worldAt(CommandSender sender, String[] args, int worldArgIndex) {
        if (args.length > worldArgIndex) {
            World named = Bukkit.getWorld(args[worldArgIndex]);
            if (named == null) {
                throw new IllegalArgumentException("unknown world '" + args[worldArgIndex] + "'");
            }
            return named;
        }
        if (sender instanceof Player player) {
            return player.getWorld();
        }
        World main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (main == null) {
            throw new IllegalArgumentException("no worlds loaded; pass an explicit world name");
        }
        return main;
    }

    private static Player asPlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        throw new IllegalArgumentException("console use requires explicit x y z arguments");
    }

    private static void usage(CommandSender sender, String hint) {
        sender.sendMessage(Component.text(hint, NamedTextColor.RED));
    }
}
