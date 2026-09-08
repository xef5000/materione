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
 * {@code /materione dev place|remove|inspect|sweep|stack|sound|painting} —
 * the world-runtime operator surface and the harness's placement driver (permission
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
            case "sound" -> sound(sender, args);
            case "painting" -> painting(sender, args);
            case "effect" -> effect(sender, args);
            default -> sender.sendMessage(Component.text(
                    "usage: /materione dev <place|remove|inspect|sweep|stack|sound|painting|effect> — "
                            + "place <block> [x y z [world]] · remove <x y z [world]> · "
                            + "inspect [x y z [world]] · sweep [--fix] · stack <itemId> · "
                            + "sound <pack>:<sound> [volume] [pitch] · painting <pack>:<variant> · "
                            + "effect <pack>:<effect> <text>",
                    NamedTextColor.RED));
        }
    }

    /** Tab completion for the {@code dev} branch. */
    public java.util.List<String> complete(String[] args) {
        if (args.length == 2) {
            return java.util.List.of("place", "remove", "inspect", "sweep", "stack", "sound",
                            "painting", "effect").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && "painting".equalsIgnoreCase(args[1])) {
            // every registered painting variant, vanilla and datapack alike —
            // a datapack variant only appears after its restart
            try {
                var registry = io.papermc.paper.registry.RegistryAccess.registryAccess()
                        .getRegistry(io.papermc.paper.registry.RegistryKey.PAINTING_VARIANT);
                String prefix = args[2].toLowerCase(Locale.ROOT);
                java.util.List<String> out = new java.util.ArrayList<>();
                for (org.bukkit.Art art : registry) {
                    org.bukkit.NamespacedKey key = registry.getKey(art);
                    if (key != null && key.toString().startsWith(prefix)) {
                        out.add(key.toString());
                    }
                }
                return out;
            } catch (RuntimeException e) {
                return java.util.List.of();
            }
        }
        return java.util.List.of();
    }

    /**
     * {@code /materione dev sound <pack>:<sound> [volume] [pitch]} — plays a
     * custom (or vanilla) sound key at the sender, the fastest way to check a
     * freshly staged pack's audio. The client resolves the key from the pack;
     * silence means the sound or the pack isn't on the client yet.
     */
    private void sound(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender, "/materione dev sound <pack>:<sound> [volume] [pitch]");
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.getLogger().info("MATERIONE-DEV-SOUND-DENIED reason=player-required");
            sender.sendMessage(Component.text(
                    "dev sound needs an in-game sender (the client resolves the key)", NamedTextColor.RED));
            return;
        }
        String key = args[2];
        float volume = parseFraction(args, 3, 1.0f);
        float pitch = parseFraction(args, 4, 1.0f);
        player.getWorld().playSound(player.getLocation(), key,
                org.bukkit.SoundCategory.MASTER, volume, pitch);
        sender.sendMessage(Component.text("dev sound: played " + key
                + " (volume " + volume + ", pitch " + pitch + ")", NamedTextColor.GREEN));
        plugin.getLogger().info("MATERIONE-DEV-SOUND key=" + key);
    }

    private static float parseFraction(String[] args, int index, float fallback) {
        if (args.length <= index) {
            return fallback;
        }
        try {
            return Float.parseFloat(args[index]);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * {@code /materione dev painting <pack>:<variant>} — places the exact
     * painting variant where the sender is looking. Vanilla placement picks a
     * random variant from the placeable tag, so this is the deterministic way
     * to check one. The variant exists server-side only after the content
     * datapack has loaded (a restart after deploy).
     */
    private void painting(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender, "/materione dev painting <pack>:<variant>");
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.getLogger().info("MATERIONE-DEV-PAINTING-DENIED reason=player-required");
            sender.sendMessage(Component.text(
                    "dev painting needs an in-game sender (it places where you look)",
                    NamedTextColor.RED));
            return;
        }
        org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(
                args[2].toLowerCase(Locale.ROOT));
        if (key == null) {
            usage(sender, "invalid painting id '" + args[2] + "' (expected <namespace>:<name>)");
            return;
        }
        org.bukkit.Art variant = io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.PAINTING_VARIANT)
                .get(key);
        if (variant == null) {
            plugin.getLogger().info("MATERIONE-DEV-PAINTING failed reason=unknown-variant key=" + key);
            boolean registryMode = plugin.datapackService() != null
                    && plugin.datapackService().isRegistryMode();
            sender.sendMessage(Component.text(
                    "painting variant '" + key + "' is not registered — "
                            + (registryMode
                                    ? "registry content re-registers at the next restart"
                                    : "the content datapack loads at the next restart")
                            + " (see /materione doctor)",
                    NamedTextColor.RED));
            return;
        }
        var hit = player.rayTraceBlocks(32);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) {
            sender.sendMessage(Component.text(
                    "dev painting: look at a wall within 32 blocks", NamedTextColor.RED));
            return;
        }
        org.bukkit.block.BlockFace face = hit.getHitBlockFace();
        // the painting hangs on the air block in front of the hit face,
        // facing back out at the player
        org.bukkit.Location spot = hit.getHitBlock().getRelative(face).getLocation()
                .add(0.5, 0.5, 0.5);
        var painting = player.getWorld().spawn(spot, org.bukkit.entity.Painting.class,
                p -> p.setFacingDirection(face.getOppositeFace(), true));
        if (!painting.setArt(variant)) {
            painting.remove();
            plugin.getLogger().info("MATERIONE-DEV-PAINTING failed reason=no-space key=" + key);
            sender.sendMessage(Component.text(
                    "dev painting: '" + key + "' doesn't fit on that wall — it needs clear wall"
                            + " space for its full block size",
                    NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text(
                "dev painting: placed " + key, NamedTextColor.GREEN));
        plugin.getLogger().info("MATERIONE-DEV-PAINTING ok=true key=" + key);
    }

    /**
     * {@code /materione dev effect <pack>:<effect> <text>} — sends the text
     * wrapped in the effect span to the sender's chat. The client animates
     * it only when the resource pack (with its shader overlay) is applied;
     * a vanilla client shows the signal color as plain colored text.
     */
    private void effect(CommandSender sender, String[] args) {
        if (args.length < 4) {
            usage(sender, "/materione dev effect <pack>:<effect> <text>");
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.getLogger().info("MATERIONE-DEV-EFFECT-DENIED reason=player-required");
            sender.sendMessage(Component.text(
                    "dev effect needs an in-game sender (the client renders the animation)",
                    NamedTextColor.RED));
            return;
        }
        String id = args[2];
        var effects = plugin.pipeline().runtime().registries()
                .view(dev.materione.core.runtime.RuntimeEffects.KEY);
        if (effects.byId(id).isEmpty()) {
            plugin.getLogger().info("MATERIONE-DEV-EFFECT failed reason=unknown-effect key=" + id);
            sender.sendMessage(Component.text(
                    "effect '" + id + "' is not registered — check /materione doctor and the"
                            + " pack's effects/ directory",
                    NamedTextColor.RED));
            return;
        }
        String text = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
        player.sendMessage(dev.materione.platform.text.GlyphText.resolve(
                "<effect:" + id + ">" + text + "</effect>"));
        plugin.getLogger().info("MATERIONE-DEV-EFFECT ok=true key=" + id);
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
