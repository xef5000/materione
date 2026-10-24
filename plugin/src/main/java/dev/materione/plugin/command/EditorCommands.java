package dev.materione.plugin.command;

import dev.materione.plugin.MaterionePlugin;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/**
 * {@code /materione editor} — the editor surface (master §9.5): open a
 * session (cloud relay by default, {@code local} for the always-available
 * direct mode), watch or answer trust prompts, revoke sessions or trusted
 * workstations, stop. Reads need {@code materione.editor}; answers/stop/
 * revoke need {@code materione.admin}.
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
        String action = args.length >= 2 ? args[1].toLowerCase() : "open";
        switch (action) {
            case "open" -> open(sender);
            case "local" -> openLocal(sender);
            case "status" -> status(sender);
            case "approve" -> {
                var service = requireService(sender);
                if (service != null) {
                    service.approve(sender, argOrEmpty(args, 2),
                            "trust".equalsIgnoreCase(argOrEmpty(args, 3)));
                }
            }
            case "deny" -> {
                var service = requireService(sender);
                if (service != null) {
                    service.deny(sender, argOrEmpty(args, 2));
                }
            }
            case "revoke" -> {
                var service = requireService(sender);
                if (service != null) {
                    String selector = argOrEmpty(args, 2);
                    if (selector.isEmpty()) {
                        service.revokeAll(sender);
                    } else {
                        service.revokeWorkstations(sender, selector);
                    }
                }
            }
            case "stop" -> stop(sender);
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
        return List.of();
    }

    // ------------------------------------------------------------------

    /** Default: cloud relay when {@code editor_mode: cloud}, else local. */
    private void open(CommandSender sender) {
        var service = requireService(sender);
        if (service != null) {
            service.openSession(sender);
        }
    }

    /** The direct-mode session (loopback/reverse-proxy; power users). */
    private void openLocal(CommandSender sender) {
        var service = requireService(sender);
        if (service != null) {
            service.openLocalSession(sender);
        }
    }

    private void status(CommandSender sender) {
        var service = plugin.editorTransport();
        if (service == null || !service.isRunning()) {
            sender.sendMessage(Component.text("editor transport: stopped", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text(
                "editor transport: running (" + service.activeEditors() + " active editor(s)"
                        + (service.localListenerRunning()
                                ? ", local listener port " + service.boundPort()
                                : ", no local listener")
                        + ")",
                NamedTextColor.GREEN));
        plugin.diagnostics().recent(32).stream()
                .filter(d -> d.code().code().startsWith("MTR-003"))
                .reduce((first, second) -> second)
                .ifPresent(latest -> sender.sendMessage(Component.text(
                        "latest editor diagnostic: " + latest.code().code() + ": " + latest.message(),
                        NamedTextColor.GRAY)));
    }

    private void stop(CommandSender sender) {
        if (!sender.hasPermission("materione.admin")) {
            sender.sendMessage(Component.text("no permission (materione.admin)", NamedTextColor.RED));
            return;
        }
        var service = plugin.editorTransport();
        if (service == null || !service.isRunning()) {
            sender.sendMessage(Component.text("editor transport already stopped", NamedTextColor.GRAY));
            return;
        }
        service.stop();
        // Stable harness marker: the transport stopped on command.
        plugin.getLogger().info("MATERIONE-EDITOR-STOPPED");
        sender.sendMessage(Component.text("editor transport stopped", NamedTextColor.GREEN));
    }

    private dev.materione.plugin.runtime.EditorTransportService requireService(CommandSender sender) {
        var service = plugin.editorTransport();
        if (service == null) {
            sender.sendMessage(Component.text(
                    "the editor transport never started this boot", NamedTextColor.RED));
        }
        return service;
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
