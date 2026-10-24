package dev.materione.plugin.command;

import dev.materione.api.admin.AdminApi;
import dev.materione.api.auth.ActorContext;
import dev.materione.plugin.MaterionePlugin;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /materione editor} — the editor surface (master §9.5): open a
 * session (cloud relay by default, {@code local} for the always-available
 * direct mode), watch or answer trust prompts, revoke sessions or trusted
 * workstations, stop. Reads need {@code materione.editor}; answers/stop/
 * revoke need {@code materione.admin}. Every action runs through the api's
 * {@link AdminApi.EditorSessionControl} — the command derives the
 * {@link ActorContext} from its sender.
 */
public final class EditorCommands {

    private static final List<String> ACTIONS =
            List.of("open", "local", "status", "approve", "deny", "revoke", "stop");

    private final MaterionePlugin plugin;

    public EditorCommands(MaterionePlugin plugin) {
        this.plugin = plugin;
    }

    public void handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("materione.editor")) {
            sender.sendMessage(Component.text("no permission (materione.editor)", NamedTextColor.RED));
            return;
        }
        var api = plugin.api();
        if (api == null) {
            sender.sendMessage(Component.text(
                    "content never loaded — the editor transport is unavailable (see startup logs)",
                    NamedTextColor.RED));
            return;
        }
        AdminApi.EditorSessionControl sessions = api.admin().editorSessions();
        String action = args.length >= 2 ? args[1].toLowerCase() : "open";
        switch (action) {
            case "open" -> mutate(sender, sessions.openSession(actor(sender)));
            case "local" -> mutate(sender, sessions.openLocalSession(actor(sender)));
            case "status" -> status(sender, api, sessions);
            case "approve" -> mutate(sender, sessions.approve(
                    actor(sender), argOrEmpty(args, 2), "trust".equalsIgnoreCase(argOrEmpty(args, 3))));
            case "deny" -> mutate(sender, sessions.deny(actor(sender), argOrEmpty(args, 2)));
            case "revoke" -> {
                String selector = argOrEmpty(args, 2);
                if (selector.isEmpty()) {
                    mutate(sender, sessions.revokeAll(actor(sender)));
                } else {
                    mutate(sender, sessions.revokeWorkstations(actor(sender), selector));
                }
            }
            case "stop" -> stop(sender, sessions);
            default -> usage(sender);
        }
    }

    public List<String> suggestions(String[] args) {
        if (args.length == 2) {
            return ACTIONS.stream().filter(a -> a.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 4 && "approve".equalsIgnoreCase(argOrEmpty(args, 1))) {
            return List.of("trust");
        }
        if (args.length == 3 && "revoke".equalsIgnoreCase(argOrEmpty(args, 1))) {
            return List.of("workstations");
        }
        return List.of();
    }

    // ------------------------------------------------------------------

    private void status(CommandSender sender, dev.materione.api.MaterioneAPI api,
            AdminApi.EditorSessionControl sessions) {
        AdminApi.EditorListenerStatus status = sessions.status();
        if (!status.running()) {
            sender.sendMessage(Component.text("editor transport: stopped", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text(
                "editor transport: running (" + status.activeSessions() + " active editor(s)"
                        + (status.localListener()
                                ? ", local listener port " + status.boundPort()
                                : ", no local listener")
                        + ", mode " + status.editorMode() + ")",
                NamedTextColor.GREEN));
        api.diagnostics().recent(32).stream()
                .filter(d -> d.code().code().startsWith("MTR-003"))
                .reduce((first, second) -> second)
                .ifPresent(latest -> sender.sendMessage(Component.text(
                        "latest editor diagnostic: " + latest.code().code() + ": " + latest.message(),
                        NamedTextColor.GRAY)));
    }

    private void stop(CommandSender sender, AdminApi.EditorSessionControl sessions) {
        if (!sender.hasPermission("materione.admin")) {
            sender.sendMessage(Component.text("no permission (materione.admin)", NamedTextColor.RED));
            return;
        }
        if (!sessions.status().running()) {
            sender.sendMessage(Component.text("editor transport already stopped", NamedTextColor.GRAY));
            return;
        }
        var result = sessions.stopListener(actor(sender));
        if (result.ok()) {
            // Stable harness marker: the transport stopped on command.
            plugin.getLogger().info("MATERIONE-EDITOR-STOPPED");
            sender.sendMessage(Component.text("editor transport stopped", NamedTextColor.GREEN));
        } else {
            mutate(sender, result);
        }
    }

    /** Renders an api rejection (permission or transport-down) as chat. */
    private void mutate(CommandSender sender, dev.materione.api.ApiResult<?> result) {
        if (!result.ok() && !result.diagnostics().isEmpty()) {
            sender.sendMessage(Component.text(
                    result.diagnostics().get(0).message(), NamedTextColor.RED));
        }
    }

    /** The command sender as an api actor: identity + live permission nodes. */
    private static ActorContext actor(CommandSender sender) {
        if (sender instanceof Player player) {
            UUID uuid = player.getUniqueId();
            return new ActorContext(player.getName(), ActorContext.Kind.PLAYER,
                    Optional.of(uuid), player::hasPermission);
        }
        return ActorContext.console();
    }

    private static String argOrEmpty(String[] args, int index) {
        return args.length > index ? args[index] : "";
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "/materione editor <open|local|status|approve <nonce> [trust]|deny <nonce>"
                        + "|revoke [workstation-prefix|workstations]|stop>",
                NamedTextColor.YELLOW));
    }
}
