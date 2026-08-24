package dev.materione.plugin.command;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.plugin.MaterionePlugin;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /materione dev place|remove|inspect|sweep|stack} — the world-runtime
 * operator surface and the harness's placement driver (permission
 * {@code materione.dev}). Every subcommand is console-runnable: coordinates
 * are required unless a player sends the command, and all world access is
 * routed through the scheduling gateway so markers always land after the
 * mutation did. {@code stack} is the exception that touches no world at all —
 * it builds one live item's stack and reports its components.
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
            case "stack" -> stack(sender, args);
            default -> sender.sendMessage(Component.text(
                    "usage: /materione dev <place|remove|inspect|sweep|stack> — "
                            + "place <block> [x y z [world]] · remove <x y z [world]> · "
                            + "inspect [x y z [world]] · sweep [--fix] · stack <itemId>",
                    NamedTextColor.RED));
        }
    }

    /** Tab completion for the {@code dev} branch. */
    public java.util.List<String> complete(String[] args) {
        if (args.length == 2) {
            return java.util.List.of("place", "remove", "inspect", "sweep", "stack").stream()
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
            if (status.startsWith("unknown-block")) {
                // fall back to the furniture runtime: one id surface for both
                plugin.furniture().devPlace(target, coords[0], coords[1], coords[2], blockId,
                        furnitureStatus -> {
                            boolean ok = furnitureStatus.startsWith("placed");
                            sender.sendMessage(Component.text("dev place: " + furnitureStatus,
                                    ok ? NamedTextColor.GREEN : NamedTextColor.RED));
                            plugin.getLogger().info("MATERIONE-DEV-FURNITURE-PLACE ok=" + ok
                                    + " " + furnitureStatus);
                        });
                return;
            }
            boolean ok = status.startsWith("placed");
            sender.sendMessage(Component.text("dev place: " + status,
                    ok ? NamedTextColor.GREEN : NamedTextColor.RED));
            plugin.getLogger().info("MATERIONE-DEV-PLACE ok=" + ok + " " + status);
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
            if (status.startsWith("not-custom")) {
                // maybe a furniture cluster is anchored here
                plugin.furniture().devRemove(world, coords[0], coords[1], coords[2], furnitureStatus -> {
                    boolean furnitureOk = furnitureStatus.startsWith("removed");
                    sender.sendMessage(Component.text("dev remove: " + furnitureStatus,
                            furnitureOk ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                    plugin.getLogger().info("MATERIONE-DEV-FURNITURE-REMOVE ok=" + furnitureOk
                            + " " + furnitureStatus);
                });
                return;
            }
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
            if (!found && plugin.furniture() != null) {
                // maybe a furniture cluster is anchored here
                plugin.furniture().devInspect(world, coords[0], coords[1], coords[2], furnitureStatus -> {
                    boolean furnitureFound = furnitureStatus.startsWith("furniture");
                    sender.sendMessage(Component.text("dev inspect: " + furnitureStatus,
                            furnitureFound ? NamedTextColor.GREEN : NamedTextColor.GRAY));
                    plugin.getLogger().info("MATERIONE-DEV-FURNITURE-INSPECT found="
                            + furnitureFound + " " + furnitureStatus);
                });
            }
        });
    }

    private void sweep(CommandSender sender, String[] args) {
        boolean fix = args.length > 2 && "--fix".equalsIgnoreCase(args[2]);
        plugin.customBlocks().devSweep(fix, status -> {
            sender.sendMessage(Component.text("dev sweep: " + status, NamedTextColor.GREEN));
            plugin.getLogger().info("MATERIONE-DEV-SWEEP " + status);
        });
    }

    /**
     * {@code /materione dev stack <itemId>} — builds one live item's stack
     * through the runtime projection and reports its item_model component
     * (console-runnable, no world or player needed). The harness pins on
     * this: the component must name the item DEFINITION id (what the
     * compiler writes to {@code assets/<ns>/items/<id>.json}), not the pack
     * model — a real client checkerboarded every modeled item when it
     * pointed at {@code <id>_model}.
     */
    private void stack(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender, "/materione dev stack <itemId>");
            return;
        }
        var pipeline = plugin.pipeline();
        var registries = pipeline == null || pipeline.runtime() == null
                ? null
                : pipeline.runtime().registries();
        if (registries == null) {
            plugin.getLogger().info("MATERIONE-STACK failed reason=no-content");
            sender.sendMessage(Component.text("no content is loaded yet", NamedTextColor.RED));
            return;
        }
        dev.materione.plugin.items.ItemIdResolver.Result resolution =
                dev.materione.plugin.items.ItemIdResolver.resolve(args[2], registries.items());
        if (resolution.status() == dev.materione.plugin.items.ItemIdResolver.Status.UNKNOWN) {
            plugin.getLogger().info("MATERIONE-STACK failed reason=unknown-item");
            sender.sendMessage(Component.text(
                    "no live item matches '" + args[2] + "'", NamedTextColor.RED));
            return;
        }
        if (resolution.status() == dev.materione.plugin.items.ItemIdResolver.Status.AMBIGUOUS) {
            plugin.getLogger().info("MATERIONE-STACK failed reason=ambiguous-item");
            sender.sendMessage(Component.text("'" + args[2] + "' is ambiguous", NamedTextColor.RED));
            return;
        }
        RuntimeItem item = registries.item(resolution.id()).orElse(null);
        if (item == null) {
            plugin.getLogger().info("MATERIONE-STACK failed reason=race");
            return; // raced an apply between resolve and fetch
        }
        ItemStack built = dev.materione.platform.items.MaterioneStacks.build(
                dev.materione.platform.items.MaterioneStacks.specOf(item));
        var meta = built.getItemMeta();
        org.bukkit.NamespacedKey model = meta == null ? null : meta.getItemModel();
        String modelText = model == null ? "none" : model.toString();
        sender.sendMessage(Component.text(
                "dev stack: " + item.id() + " item_model=" + modelText, NamedTextColor.GREEN));
        plugin.getLogger().info("MATERIONE-STACK id=" + item.id() + " item_model=" + modelText);
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
