package dev.materione.plugin.command;

import dev.materione.api.NamespacedId;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.platform.ProbeResult;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.MaterionePlugin;
import dev.materione.plugin.MaterioneVersion;
import dev.materione.plugin.gui.ContentBrowser;
import dev.materione.plugin.items.Give;
import dev.materione.plugin.items.ItemIdResolver;
import dev.materione.plugin.runtime.PipelineService;
import dev.materione.platform.items.MaterioneStacks;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /materione version|doctor|reload} — the in-game surface (master
 * §9.5: "commands operate, the editor authors"). Permissions follow the
 * master §9.2 taxonomy: reads need {@code materione.editor}, reload needs
 * {@code materione.pipeline}.
 */
public final class MaterioneCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("version", "doctor", "reload", "dev", "give", "browser", "editor", "gui");

    private final MaterionePlugin plugin;
    private final DevCommands devCommands;
    private final ContentBrowser browser;
    private final EditorCommands editorCommands;
    private final GuiCommands guiCommands;

    public MaterioneCommand(MaterionePlugin plugin) {
        this.plugin = plugin;
        this.devCommands = new DevCommands(plugin);
        this.browser = new ContentBrowser(plugin);
        this.editorCommands = new EditorCommands(plugin);
        this.guiCommands = new GuiCommands(plugin);
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            String @NotNull [] args) {
        String sub = args.length == 0 ? "version" : args[0].toLowerCase();
        switch (sub) {
            case "version" -> {
                sender.sendMessage(Component.text(
                        "Materione " + MaterioneVersion.VERSION, NamedTextColor.GREEN));
                // Stable harness marker (harness/README.md).
                plugin.getLogger().info("MATERIONE-VERSION " + MaterioneVersion.VERSION);
            }
            case "doctor" -> {
                if (!sender.hasPermission("materione.editor")) {
                    sender.sendMessage(Component.text("no permission (materione.editor)", NamedTextColor.RED));
                    return true;
                }
                doctor(sender);
            }
            case "reload" -> {
                if (!sender.hasPermission("materione.pipeline")) {
                    sender.sendMessage(Component.text("no permission (materione.pipeline)", NamedTextColor.RED));
                    return true;
                }
                // composed reload: pipeline → compile → apply → deliver (§4),
                // through the one reload path the editor and future MCP callers use
                var api = plugin.api();
                if (api == null) {
                    sender.sendMessage(Component.text(
                            "content never loaded — there is nothing to reload (see startup logs)",
                            NamedTextColor.RED));
                    return true;
                }
                api.pipeline().reload(
                                dev.materione.api.auth.ActorContext.console(),
                                dev.materione.api.pipeline.PipelineApi.ReloadRequest.manual(
                                        new dev.materione.api.pipeline.ReloadScope.Everything(),
                                        dev.materione.api.pipeline.PipelineApi.DeliveryMode.PUBLISH_DEFAULT))
                        .thenAccept(result -> {
                            var summary = result.value();
                            if (summary == null) {
                                sender.sendMessage(Component.text(
                                        "reload failed (see diagnostics)",
                                        NamedTextColor.RED));
                                return;
                            }
                            if (summary.skipped()) {
                                sender.sendMessage(Component.text(
                                        "reload failed (see diagnostics)",
                                        NamedTextColor.RED));
                            } else if (summary.noop()) {
                                sender.sendMessage(Component.text(
                                        "reload complete — no content changes (apply and delivery skipped)",
                                        NamedTextColor.GREEN));
                            } else {
                                String packText = summary.packSha256() == null
                                        ? "pack bytes unchanged"
                                        : ", pack " + summary.packSha256().substring(0, 12) + "…";
                                sender.sendMessage(Component.text(
                                        "reload complete — " + summary.itemsLive() + " item(s) live, "
                                                + summary.quarantined() + " quarantined" + packText,
                                        NamedTextColor.GREEN));
                            }
                            // Vault or LuckPerms installed since boot is picked up by the reload (logic.md §4.5)
                            plugin.refreshLogicProviders();
                            // Stable harness marker: a real reload ran.
                            plugin.getLogger().info("MATERIONE-RELOAD-OK items=" + summary.itemsLive());
                        });
            }
            case "give" -> give(sender, args);
            case "browser" -> {
                if (!sender.hasPermission("materione.give")) {
                    sender.sendMessage(Component.text("no permission (materione.give)", NamedTextColor.RED));
                    return true;
                }
                if (sender instanceof Player player) {
                    browser.open(player);
                } else {
                    plugin.getLogger().info("MATERIONE-BROWSER-DENIED reason=player-only");
                    sender.sendMessage(Component.text(
                            "the content browser needs a player — open it in game", NamedTextColor.RED));
                }
            }
            case "dev" -> devCommands.handle(sender, args);
            case "editor" -> editorCommands.handle(sender, args);
            case "gui" -> guiCommands.handle(sender, args);
            default -> sender.sendMessage(Component.text(
                    "usage: /materione <version|doctor|reload|dev|give|browser|editor|gui>", NamedTextColor.RED));
        }
        return true;
    }

    /**
     * {@code /materione give <id> [player] [amount]} — hands out one live
     * custom item (permission {@code materione.give}). Console senders must
     * name a target; the hand-out itself hops to the target's region thread.
     */
    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("materione.give")) {
            sender.sendMessage(Component.text("no permission (materione.give)", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "usage: /materione give <itemId> [player] [amount]", NamedTextColor.RED));
            return;
        }
        var registries = liveRegistries();
        if (registries == null) {
            plugin.getLogger().info("MATERIONE-GIVE-FAILED reason=no-content");
            sender.sendMessage(Component.text("no content is loaded yet", NamedTextColor.RED));
            return;
        }
        ItemIdResolver.Result resolution = ItemIdResolver.resolve(args[1], registries.items());
        if (resolution.status() == ItemIdResolver.Status.UNKNOWN) {
            plugin.getLogger().info("MATERIONE-GIVE-FAILED reason=unknown-item");
            sender.sendMessage(Component.text(
                    "no live item matches '" + args[1] + "' — try /materione browser",
                    NamedTextColor.RED));
            return;
        }
        if (resolution.status() == ItemIdResolver.Status.AMBIGUOUS) {
            sender.sendMessage(Component.text(
                    "'" + args[1] + "' is ambiguous: " + resolution.candidates().stream()
                            .map(NamespacedId::toString)
                            .limit(8)
                            .reduce((a, b) -> a + ", " + b).orElse(""),
                    NamedTextColor.RED));
            return;
        }
        Player target = resolveTarget(sender, args.length >= 3 ? args[2] : null);
        if (target == null) {
            return; // marker + message already sent by resolveTarget
        }
        int amount = args.length >= 4 ? Give.clampAmount(args[3]) : 1;
        RuntimeItem item = registries.item(resolution.id()).orElse(null);
        if (item == null) {
            return; // raced an apply between resolve and fetch: next try succeeds
        }
        ItemStack template = MaterioneStacks.build(MaterioneStacks.specOf(item));
        NamespacedId id = item.id();
        plugin.scheduling().executeEntity(plugin, target, () -> {
            Give.deliver(target, template, amount);
            target.sendMessage(Component.text("Given ", NamedTextColor.GREEN)
                    .append(Component.text(id.toString(), NamedTextColor.AQUA))
                    .append(Component.text(" ×" + amount, NamedTextColor.GREEN)));
            if (!(sender instanceof Player)) {
                plugin.getLogger().info(
                        "MATERIONE-GIVE-OK id=" + id + " player=" + target.getName()
                                + " amount=" + amount);
            }
        });
    }

    private Player resolveTarget(CommandSender sender, String name) {
        if (name != null) {
            Player named = Bukkit.getPlayerExact(name);
            if (named == null) {
                plugin.getLogger().info("MATERIONE-GIVE-FAILED reason=no-such-player player=" + name);
                sender.sendMessage(Component.text("no online player named '" + name + "'",
                        NamedTextColor.RED));
            }
            return named;
        }
        if (sender instanceof Player self) {
            return self;
        }
        plugin.getLogger().info("MATERIONE-GIVE-DENIED reason=player-required");
        sender.sendMessage(Component.text(
                "console must name a target: /materione give <id> <player> [amount]",
                NamedTextColor.RED));
        return null;
    }

    private RuntimeRegistries liveRegistries() {
        PipelineService pipeline = plugin.pipeline();
        return pipeline == null || pipeline.runtime() == null
                ? null
                : pipeline.runtime().registries();
    }

    /** Tab suggestions for the {@code give} branch. */
    private List<String> completeGive(String[] args) {
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            var registries = liveRegistries();
            if (registries == null) {
                return List.of();
            }
            var out = new ArrayList<String>();
            for (RuntimeItem item : registries.items()) {
                String full = item.id().toString();
                if (full.startsWith(prefix)) {
                    out.add(full);
                } else if (!prefix.contains(":")
                        && item.id().value().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(item.id().value());
                }
            }
            return out.stream().sorted().limit(60).toList();
        }
        if (args.length == 3) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 4) {
            return List.of("1", "16", "32", "64").stream()
                    .filter(s -> s.startsWith(args[3])).toList();
        }
        return List.of();
    }

    /**
     * Renders the structured {@link dev.materione.api.admin.AdminApi.DoctorReport}
     * — the same record the web dashboard shows (api.md §3.8): one source,
     * two presentations.
     */
    private void doctor(CommandSender sender) {
        sender.sendMessage(Component.text("Materione doctor — " + MaterioneVersion.VERSION,
                NamedTextColor.GREEN));

        var report = plugin.api() == null ? null : plugin.api().admin().doctor();
        if (report == null) {
            sender.sendMessage(Component.text(
                    "content never loaded — the api is not wired (see startup logs)",
                    NamedTextColor.RED));
            plugin.getLogger().info("MATERIONE-DOCTOR-OK");
            return;
        }

        if (report.platform() == null) {
            sender.sendMessage(Component.text(
                    "platform: UNSUPPORTED — " + report.unsupportedReason(), NamedTextColor.RED));
        } else {
            var descriptor = report.platform();
            sender.sendMessage(Component.text(
                    "platform: " + descriptor.minecraftVersion()
                            + (descriptor.folia() ? " (Folia)" : " (Paper)")
                            + ", capabilities " + descriptor.capabilities().size()
                            + (descriptor.geyserPresent() ? ", Geyser" : "")
                            + (descriptor.viaVersionPresent() ? ", ViaVersion" : ""),
                    NamedTextColor.GRAY));
        }

        sender.sendMessage(Component.text(
                "settings: version " + (report.settingsVersion() == null
                        ? "not loaded" : report.settingsVersion())
                        + (report.settingsLoadDiagnostics() > 0
                                ? " (" + report.settingsLoadDiagnostics() + " load diagnostic(s))"
                                : ""),
                NamedTextColor.GRAY));

        sender.sendMessage(Component.text(
                "storage: " + (report.storageFormatVersion() == null
                        ? "not initialized"
                        : "format v" + report.storageFormatVersion()),
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                "allocation table: " + (report.allocationLiveCount() < 0
                        ? "not loaded"
                        : report.allocationLiveCount() + " live"
                                + (report.allocationCorrupt() ? " (CORRUPT — rebuild available)" : "")
                                + ", " + (report.storageFormatVersion() == null
                                        ? "?" : "format v" + report.storageFormatVersion())),
                report.allocationCorrupt() ? NamedTextColor.RED : NamedTextColor.GRAY));

        sender.sendMessage(Component.text(
                "runtime: " + (report.packSha256() == null && report.liveItems() < 0
                        ? "not started"
                        : report.liveItems() + " item(s), pack "
                                + (report.packSha256() == null
                                        ? "none" : report.packSha256().substring(0, 12) + "…")),
                NamedTextColor.GRAY));

        var last = report.lastApply();
        sender.sendMessage(Component.text(
                "pre-apply report (last apply): "
                        + (last == null ? "none yet"
                                : "+" + last.added() + " ~" + last.changed()
                                        + " -" + last.removed() + " items, "
                                        + "+" + last.blocksAdded() + "/-" + last.blocksRemoved()
                                        + " blocks, +" + last.furnitureAdded() + "/-"
                                        + last.furnitureRemoved() + " furniture"),
                NamedTextColor.GRAY));

        sender.sendMessage(Component.text(
                "staged-restart: " + (report.pendingRestartCount() < 0
                        ? "not initialized"
                        : report.pendingRestartCount() + " pending change(s)"),
                NamedTextColor.GRAY));

        if (report.datapackRegistryMode() != null && report.datapackRegistryMode()) {
            sender.sendMessage(Component.text(
                    report.datapackSummary()
                            + (report.datapackPendingRestart()
                                    ? " — RESTART REQUIRED (a reload changed registered content)"
                                    : " — current"),
                    report.datapackPendingRestart() ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
        } else if (report.datapackRegistryMode() != null) {
            sender.sendMessage(Component.text(
                    "datapack: " + report.datapackSummary().replaceFirst("^datapack: ", "")
                            + (report.datapackPendingRestart()
                                    ? " — RESTART REQUIRED (paintings/jukebox stay vanilla until then)"
                                    : report.datapackAccepted()
                                            ? " — current, accepted by the server"
                                            : " — NOT ACCEPTED by the server's pack repository"
                                                    + " (paintings/jukebox do not exist)"),
                    report.datapackPendingRestart() || !report.datapackAccepted()
                            ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
        }

        sender.sendMessage(Component.text(
                "delivery: " + (report.delivery() == null || !report.delivery().running()
                        ? "not started"
                        : report.delivery().packUrl()),
                NamedTextColor.GRAY));
        if (!report.acceptanceLines().isEmpty()) {
            sender.sendMessage(Component.text(
                    "pack acceptance: " + String.join(", ", report.acceptanceLines())
                            + " — glyph placeholders stay literal until a player loads the pack",
                    NamedTextColor.GRAY));
        }

        sender.sendMessage(Component.text(
                "registries: " + report.registryCount()
                        + " (" + report.registryEntryCount() + " entries)",
                NamedTextColor.GRAY));
        report.registryLines().forEach(line ->
                sender.sendMessage(Component.text("  " + line, NamedTextColor.DARK_GRAY)));

        sender.sendMessage(Component.text(
                "quarantined: " + Math.max(0, report.quarantinedCount()),
                NamedTextColor.GRAY));

        sender.sendMessage(Component.text(
                "diagnostics: " + report.errorCount() + " error, "
                        + report.warnCount() + " warn, " + report.infoCount() + " info",
                NamedTextColor.GRAY));

        // Stable harness marker: doctor completed against an M0-empty system.
        plugin.getLogger().info("MATERIONE-DOCTOR-OK");
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            String @NotNull [] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length >= 2 && "dev".equals(args[0].toLowerCase())) {
            return devCommands.complete(args);
        }
        if (args.length >= 2 && "editor".equals(args[0].toLowerCase())) {
            return editorCommands.suggestions(args);
        }
        if (args.length >= 2 && "give".equals(args[0].toLowerCase())) {
            return completeGive(args);
        }
        if (args.length >= 2 && "gui".equals(args[0].toLowerCase())) {
            return guiCommands.complete(args);
        }
        return List.of();
    }
}
