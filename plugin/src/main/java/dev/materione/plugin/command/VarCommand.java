package dev.materione.plugin.command;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.api.logic.variable.VariablesApi;
import dev.materione.plugin.MaterionePlugin;
import dev.materione.plugin.logic.variable.VariableService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /materione var get|set|list <player> [<var>] [<value>]} — reads and writes declared
 * persistent variables (logic.md §3.3; permission {@code materione.var}). A player variable is held by
 * the named player, who must be online; a server variable ignores the player argument, so write
 * {@code -} for it. The command calls the API, so it shares the checks and the stores the flows use.
 */
public final class VarCommand {

    private static final List<String> ACTIONS = List.of("get", "set", "list");

    private final MaterionePlugin plugin;

    public VarCommand(MaterionePlugin plugin) {
        this.plugin = plugin;
    }

    /** Handles {@code var <action> …}; {@code args[0]} is the {@code var} token itself. */
    public void handle(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (!ACTIONS.contains(action)) {
            usage(sender);
            return;
        }
        if (!sender.hasPermission(VariableService.PERMISSION)) {
            sender.sendMessage(Component.text("no permission (" + VariableService.PERMISSION + ")",
                    NamedTextColor.RED));
            return;
        }
        VariableService variables = plugin.variableService();
        if (variables == null) {
            sender.sendMessage(Component.text("variables are not available", NamedTextColor.RED));
            return;
        }
        VariablesApi api = variables.api();
        if ("list".equals(action)) {
            list(sender, api, args.length >= 3 ? args[2] : null);
            return;
        }
        if (args.length < 4) {
            usage(sender);
            return;
        }
        Optional<VariableDeclaration> declared = resolve(args[3], api);
        if (declared.isEmpty()) {
            sender.sendMessage(Component.text("no declared variable named '" + args[3] + "'", NamedTextColor.RED));
            return;
        }
        Target target = target(sender, args[2], declared.get());
        if (target == null) {
            return; // the reason is already sent
        }
        if ("get".equals(action)) {
            get(sender, api, declared.get(), target);
        } else {
            if (args.length < 5) {
                usage(sender);
                return;
            }
            set(sender, api, declared.get(), target, String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)));
        }
    }

    /** Tab suggestions: the action, then the player (or {@code -}), then declared variable ids. */
    public List<String> complete(String[] args, VariableService variables) {
        if (args.length == 2) {
            return filter(ACTIONS, args[1]);
        }
        if (args.length == 3) {
            List<String> names = new ArrayList<>();
            names.add("-");
            Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
            return filter(names, args[2]);
        }
        if (args.length == 4 && !"list".equalsIgnoreCase(args[1]) && variables != null) {
            List<String> ids = variables.declarations().stream().map(d -> d.id().toString()).toList();
            return filter(ids, args[3]);
        }
        return List.of();
    }

    // ------------------------------------------------------------------

    private void list(CommandSender sender, VariablesApi api, String playerName) {
        Target target = playerName == null ? new Target(null, null) : target(sender, playerName, null);
        if (target == null) {
            return;
        }
        List<VariableDeclaration> declared = new ArrayList<>(api.declarations());
        if (declared.isEmpty()) {
            sender.sendMessage(Component.text("no variables are declared", NamedTextColor.GRAY));
            return;
        }
        for (VariableDeclaration declaration : declared) {
            Object value = api.get(declaration.id(), holderFor(declaration, target)).orElse(null);
            sender.sendMessage(Component.text(declaration.id() + " ", NamedTextColor.AQUA)
                    .append(Component.text("[" + declaration.scope().id() + ", " + declaration.type().id() + "] ",
                            NamedTextColor.GRAY))
                    .append(Component.text("= " + shown(value), NamedTextColor.WHITE)));
        }
    }

    private void get(CommandSender sender, VariablesApi api, VariableDeclaration declared, Target target) {
        Object value = api.get(declared.id(), holderFor(declared, target)).orElse(null);
        sender.sendMessage(Component.text(declared.id() + " = " + shown(value), NamedTextColor.GREEN)
                .append(Component.text(holderNote(declared, target), NamedTextColor.GRAY)));
    }

    private void set(CommandSender sender, VariablesApi api, VariableDeclaration declared, Target target, String raw) {
        Optional<Object> value = declared.type().coerce(declared.type() == VariableType.STRING ? raw : raw.trim());
        if (value.isEmpty()) {
            sender.sendMessage(Component.text("'" + raw + "' is not a " + declared.type().id(), NamedTextColor.RED));
            return;
        }
        ApiResult<Object> result = api.set(actor(sender), declared.id(), holderFor(declared, target), value.get());
        if (!result.ok()) {
            sender.sendMessage(Component.text(result.diagnostics().isEmpty()
                    ? "the value could not be stored"
                    : result.diagnostics().get(0).message(), NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text(declared.id() + " = " + shown(result.value()), NamedTextColor.GREEN)
                .append(Component.text(holderNote(declared, target), NamedTextColor.GRAY)));
    }

    /** The holder of a declaration for this command: the named player for player scope, nobody for server. */
    private static UUID holderFor(VariableDeclaration declared, Target target) {
        return declared.scope() == VariableScope.PLAYER && target != null ? target.uuid() : null;
    }

    private static String holderNote(VariableDeclaration declared, Target target) {
        if (declared.scope() == VariableScope.SERVER) {
            return " (server)";
        }
        return target == null || target.name() == null ? "" : " (player: " + target.name() + ")";
    }

    /**
     * The holder a command names: a server variable ignores it; a player variable needs an online
     * player. Prints the refusal and returns null when there is none.
     */
    private Target target(CommandSender sender, String name, VariableDeclaration declared) {
        if (declared != null && declared.scope() == VariableScope.SERVER) {
            return new Target(null, null);
        }
        if (name.equals("-")) {
            if (declared == null) {
                return new Target(null, null);
            }
            sender.sendMessage(Component.text("'" + declared.id() + "' is a player variable: name a player",
                    NamedTextColor.RED));
            return null;
        }
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            sender.sendMessage(Component.text("no online player named '" + name + "' (player variables live "
                    + "on the player)", NamedTextColor.RED));
            return null;
        }
        return new Target(player.getUniqueId(), player.getName());
    }

    /** A reference: {@code pack:key}, or a bare key when exactly one declaration has it. */
    private static Optional<VariableDeclaration> resolve(String text, VariablesApi api) {
        if (text.indexOf(':') >= 0) {
            try {
                return api.declaration(NamespacedId.parse(text.toLowerCase(Locale.ROOT)));
            } catch (IllegalArgumentException malformed) {
                return Optional.empty();
            }
        }
        List<VariableDeclaration> matches = api.declarations().stream()
                .filter(declaration -> declaration.id().value().equalsIgnoreCase(text))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private static ActorContext actor(CommandSender sender) {
        if (sender instanceof Player player) {
            return ActorContext.player(player.getUniqueId(), player.getName(), player::hasPermission);
        }
        return ActorContext.console();
    }

    /** A value as the command prints it: whole numbers without a fraction. */
    private static String shown(Object value) {
        if (value instanceof Double number && number == Math.rint(number) && Math.abs(number) < 1e15) {
            return Long.toString(number.longValue());
        }
        return String.valueOf(value);
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "usage: /materione var get|set <player|-> <pack:key> [value]  |  /materione var list [player]",
                NamedTextColor.YELLOW));
    }

    /** The named holder of one command: its id and name, both null for a server variable or no player. */
    private record Target(UUID uuid, String name) {}
}
