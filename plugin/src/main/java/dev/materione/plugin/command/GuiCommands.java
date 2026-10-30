package dev.materione.plugin.command;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.runtime.RuntimeApi;
import dev.materione.plugin.MaterionePlugin;
import dev.materione.plugin.gui.GuiRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /materione gui open <id> [player]} — opens a live GUI for a player (gui.md §5;
 * permission {@code materione.gui.open}). The command derives an {@link ActorContext} from its
 * sender and calls the api; it never builds or opens a window itself, so the in-game path and
 * the editor's preview share one implementation.
 *
 * <p>A bare id ({@code shop}) matches a live GUI by value and must be unambiguous; a qualified id
 * ({@code demo:shop}) must be live. A console sender must name a player, as {@code give} does.
 */
public final class GuiCommands {

    private static final List<String> ACTIONS = List.of("open");

    private final MaterionePlugin plugin;

    public GuiCommands(MaterionePlugin plugin) {
        this.plugin = plugin;
    }

    /** Handles {@code gui <action> …}; {@code args[0]} is the {@code gui} token itself. */
    public void handle(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (!"open".equals(action)) {
            usage(sender);
            return;
        }
        if (!sender.hasPermission(GuiRuntime.OPEN_PERMISSION)) {
            sender.sendMessage(Component.text("no permission (" + GuiRuntime.OPEN_PERMISSION + ")",
                    NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            usage(sender);
            return;
        }
        var api = plugin.api();
        if (api == null) {
            sender.sendMessage(Component.text("no content is loaded yet", NamedTextColor.RED));
            return;
        }
        RuntimeApi.GuiRuntimeApi guis = api.runtime().gui();
        Resolution resolution = resolve(args[2], guis.guis());
        if (resolution.status() != Status.RESOLVED) {
            plugin.getLogger().info("MATERIONE-GUI-FAILED reason=not-live");
            sender.sendMessage(Component.text(resolution.status() == Status.AMBIGUOUS
                    ? "'" + args[2] + "' is ambiguous: " + resolution.candidates().stream()
                            .map(NamespacedId::toString).reduce((a, b) -> a + ", " + b).orElse("")
                    : "no live GUI matches '" + args[2] + "' — is its pack loaded and does it compile? "
                            + "(see /materione doctor)",
                    NamedTextColor.RED));
            return;
        }
        NamespacedId id = resolution.id();
        Player target = resolveTarget(sender, args.length >= 4 && args[3].indexOf('=') < 0 ? args[3] : null);
        if (target == null) {
            return; // the marker and the message are already out
        }
        guis.open(actor(sender), id, target.getUniqueId(), argumentsOf(args)).thenAccept(result -> {
            if (!result.ok()) {
                refused(sender, result);
                return;
            }
            sender.sendMessage(Component.text("Opened ", NamedTextColor.GREEN)
                    .append(Component.text(id.toString(), NamedTextColor.AQUA))
                    .append(Component.text(" for " + target.getName(), NamedTextColor.GREEN)));
        });
    }

    /**
     * The {@code name=value} tokens after the id: the arguments the GUI opens with (read by its rules
     * as {@code gui.args.<name>}). Typed arguments and the open commands that take them are the open
     * specification's; here they are plain text. A player name never holds {@code =}, so a token with
     * one is never mistaken for the target.
     */
    static java.util.Map<String, String> argumentsOf(String[] args) {
        var out = new java.util.LinkedHashMap<String, String>();
        for (int i = 3; i < args.length; i++) {
            int equals = args[i].indexOf('=');
            if (equals > 0) {
                out.put(args[i].substring(0, equals), args[i].substring(equals + 1));
            }
        }
        return out;
    }

    /** Tab suggestions: the action, then live GUI ids, then online player names. */
    public List<String> complete(String[] args) {
        if (args.length == 2) {
            return ACTIONS.stream().filter(a -> a.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && "open".equalsIgnoreCase(args[1])) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            var out = new ArrayList<String>();
            for (RuntimeApi.GuiView gui : guisOrEmpty()) {
                String full = gui.id().toString();
                if (full.startsWith(prefix)) {
                    out.add(full);
                } else if (!prefix.contains(":") && gui.id().value().startsWith(prefix)) {
                    out.add(gui.id().value());
                }
            }
            return out.stream().sorted().limit(60).toList();
        }
        if (args.length == 4 && "open".equalsIgnoreCase(args[1])) {
            String prefix = args[3].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    // ------------------------------------------------------------------

    /** The outcome of resolving a typed GUI reference. */
    enum Status { RESOLVED, AMBIGUOUS, UNKNOWN }

    /** A resolved id, or the candidates an ambiguous bare value could mean. */
    record Resolution(Status status, NamespacedId id, List<NamespacedId> candidates) {}

    /**
     * Turns a typed GUI reference into a live id: a qualified one must be live; a bare one matches
     * live GUIs by value and reports ambiguity instead of guessing. Package-private for the test.
     */
    static Resolution resolve(String raw, List<RuntimeApi.GuiView> live) {
        String text = raw.trim();
        if (text.isEmpty()) {
            return new Resolution(Status.UNKNOWN, null, List.of());
        }
        if (text.indexOf(':') >= 0) {
            NamespacedId parsed;
            try {
                parsed = NamespacedId.parse(text);
            } catch (IllegalArgumentException malformed) {
                return new Resolution(Status.UNKNOWN, null, List.of());
            }
            boolean isLive = live.stream().anyMatch(gui -> gui.id().equals(parsed));
            return isLive
                    ? new Resolution(Status.RESOLVED, parsed, List.of())
                    : new Resolution(Status.UNKNOWN, null, List.of());
        }
        List<NamespacedId> matches = live.stream()
                .map(RuntimeApi.GuiView::id)
                .filter(id -> id.value().equalsIgnoreCase(text))
                .toList();
        if (matches.size() == 1) {
            return new Resolution(Status.RESOLVED, matches.get(0), List.of());
        }
        if (matches.size() > 1) {
            return new Resolution(Status.AMBIGUOUS, null, matches);
        }
        return new Resolution(Status.UNKNOWN, null, List.of());
    }

    private List<RuntimeApi.GuiView> guisOrEmpty() {
        var api = plugin.api();
        return api == null ? List.of() : api.runtime().gui().guis();
    }

    /** The player the window opens for: the named one, or the sender when they are a player. */
    private Player resolveTarget(CommandSender sender, String name) {
        if (name != null) {
            Player named = Bukkit.getPlayerExact(name);
            if (named == null) {
                plugin.getLogger().info("MATERIONE-GUI-FAILED reason=no-such-player player=" + name);
                sender.sendMessage(Component.text("no online player named '" + name + "'", NamedTextColor.RED));
            }
            return named;
        }
        if (sender instanceof Player self) {
            return self;
        }
        plugin.getLogger().info("MATERIONE-GUI-DENIED reason=player-required");
        sender.sendMessage(Component.text(
                "console must name a target: /materione gui open <id> <player>", NamedTextColor.RED));
        return null;
    }

    private void refused(CommandSender sender, ApiResult<?> result) {
        plugin.getLogger().info("MATERIONE-GUI-FAILED reason=refused");
        Optional<String> message = result.diagnostics().stream()
                .findFirst()
                .map(diagnostic -> diagnostic.message());
        sender.sendMessage(Component.text(message.orElse("the GUI could not be opened"), NamedTextColor.RED));
    }

    /** The command sender as an api actor: identity and live permission nodes. */
    private static ActorContext actor(CommandSender sender) {
        if (sender instanceof Player player) {
            return ActorContext.player(player.getUniqueId(), player.getName(), player::hasPermission);
        }
        return ActorContext.console();
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "usage: /materione gui open <guiId> [player] [name=value...]", NamedTextColor.YELLOW));
    }
}
