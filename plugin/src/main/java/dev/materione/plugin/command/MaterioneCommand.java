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
            List.of("version", "doctor", "reload", "dev", "give", "browser");

    private final MaterionePlugin plugin;
    private final DevCommands devCommands;
    private final ContentBrowser browser;

    public MaterioneCommand(MaterionePlugin plugin) {
        this.plugin = plugin;
        this.devCommands = new DevCommands(plugin);
        this.browser = new ContentBrowser(plugin);
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
                // composed reload: pipeline → compile → apply → deliver (§4)
                var summary = plugin.reloadContent();
                // re-open → re-check → re-freeze (D5): windows reopen around the run
                plugin.registries().reopenAllWindows();
                if (summary.skipped()) {
                    sender.sendMessage(Component.text(
                            "reload skipped — a run was already in flight or aborted (see diagnostics)",
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
                plugin.registries().freezeAll();
                // Stable harness marker: a real reload ran.
                plugin.getLogger().info("MATERIONE-RELOAD-OK items=" + summary.itemsLive());
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
            default -> sender.sendMessage(Component.text(
                    "usage: /materione <version|doctor|reload|dev|give|browser>", NamedTextColor.RED));
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

    private void doctor(CommandSender sender) {
        sender.sendMessage(Component.text("Materione doctor — " + MaterioneVersion.VERSION,
                NamedTextColor.GREEN));

        PlatformDescriptor descriptor = plugin.descriptorOrNull();
        if (descriptor == null) {
            ProbeResult.Unsupported unsupported =
                    (ProbeResult.Unsupported) plugin.probeResult();
            sender.sendMessage(Component.text(
                    "platform: UNSUPPORTED — " + unsupported.reason(), NamedTextColor.RED));
        } else {
            sender.sendMessage(Component.text(
                    "platform: " + descriptor.minecraftVersion()
                            + (descriptor.folia() ? " (Folia)" : " (Paper)")
                            + ", capabilities " + descriptor.capabilities().size()
                            + (descriptor.geyserPresent() ? ", Geyser" : "")
                            + (descriptor.viaVersionPresent() ? ", ViaVersion" : ""),
                    NamedTextColor.GRAY));
        }

        var settings = plugin.settings();
        sender.sendMessage(Component.text(
                "settings: version " + (settings == null ? "not loaded" : settings.version())
                        + (settings != null && !settings.diagnostics().isEmpty()
                                ? " (" + settings.diagnostics().size() + " load diagnostic(s))"
                                : ""),
                NamedTextColor.GRAY));

        var storage = plugin.storage();
        sender.sendMessage(Component.text(
                "storage: " + (storage == null
                        ? "not initialized"
                        : "format v" + storage.formatVersion()),
                NamedTextColor.GRAY));
        var allocation = plugin.pipeline() == null ? null : plugin.pipeline().allocation();
        sender.sendMessage(Component.text(
                "allocation table: " + (allocation == null
                        ? "not loaded"
                        : allocation.liveCount() + " live"
                                + (allocation.isCorrupt() ? " (CORRUPT — rebuild available)" : "")
                                + ", " + (plugin.storage() == null ? "?" : "format v" + plugin.storage().formatVersion())),
                allocation != null && allocation.isCorrupt() ? NamedTextColor.RED : NamedTextColor.GRAY));

        var runtime = plugin.pipeline() == null ? null : plugin.pipeline().runtime();
        sender.sendMessage(Component.text(
                "runtime: " + (runtime == null ? "not started"
                        : runtime.registries().itemCount() + " item(s), pack "
                                + (runtime.livePackSha256().isBlank() ? "none" : runtime.livePackSha256().substring(0, 12) + "…")),
                NamedTextColor.GRAY));

        var lastReport = plugin.pipeline() == null ? null : plugin.pipeline().lastReport();
        sender.sendMessage(Component.text(
                "pre-apply report (last apply): "
                        + (lastReport == null ? "none yet"
                                : "+" + lastReport.added() + " ~" + lastReport.changed()
                                        + " -" + lastReport.removed() + " items, "
                                        + "+" + lastReport.blocksAdded() + "/-" + lastReport.blocksRemoved()
                                        + " blocks, +" + lastReport.furnitureAdded() + "/-"
                                        + lastReport.furnitureRemoved() + " furniture"),
                NamedTextColor.GRAY));

        var restartStaging = plugin.restartStaging();
        sender.sendMessage(Component.text(
                "staged-restart: " + (restartStaging == null ? "not initialized"
                        : restartStaging.load().size() + " pending change(s)"),
                NamedTextColor.GRAY));

        var delivery = plugin.delivery();
        sender.sendMessage(Component.text(
                "delivery: " + (delivery == null || delivery.currentUrl().isEmpty()
                        ? "not started"
                        : delivery.currentUrl()),
                NamedTextColor.GRAY));

        sender.sendMessage(Component.text(
                "registries: " + plugin.registries().registryCount()
                        + " (" + plugin.registries().totalEntries() + " entries)",
                NamedTextColor.GRAY));
        plugin.registries().doctorLines().forEach(line ->
                sender.sendMessage(Component.text("  " + line, NamedTextColor.DARK_GRAY)));

        QuarantineTracker quarantine = plugin.quarantine();
        sender.sendMessage(Component.text(
                "quarantined: " + (quarantine == null ? 0 : quarantine.size()),
                NamedTextColor.GRAY));

        var diagnostics = plugin.diagnostics();
        sender.sendMessage(Component.text(
                "diagnostics: " + diagnostics.count(dev.materione.api.diagnostics.Severity.ERROR)
                        + " error, " + diagnostics.count(dev.materione.api.diagnostics.Severity.WARN)
                        + " warn, " + diagnostics.count(dev.materione.api.diagnostics.Severity.INFO)
                        + " info",
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
        if (args.length >= 2 && "give".equals(args[0].toLowerCase())) {
            return completeGive(args);
        }
        return List.of();
    }
}
