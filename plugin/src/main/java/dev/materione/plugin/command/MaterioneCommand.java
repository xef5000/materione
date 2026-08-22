package dev.materione.plugin.command;

import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.platform.ProbeResult;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.plugin.MaterionePlugin;
import dev.materione.plugin.MaterioneVersion;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /materione version|doctor|reload} — the in-game surface (master
 * §9.5: "commands operate, the editor authors"). Permissions follow the
 * master §9.2 taxonomy: reads need {@code materione.editor}, reload needs
 * {@code materione.pipeline}.
 */
public final class MaterioneCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("version", "doctor", "reload");

    private final MaterionePlugin plugin;

    public MaterioneCommand(MaterionePlugin plugin) {
        this.plugin = plugin;
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
                } else if (summary.packSha256() == null) {
                    sender.sendMessage(Component.text(
                            "reload complete — pack unchanged (no-op: apply and delivery skipped)",
                            NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text(
                            "reload complete — " + summary.itemsLive() + " item(s) live, "
                                    + summary.quarantined() + " quarantined, pack "
                                    + summary.packSha256().substring(0, 12) + "…",
                            NamedTextColor.GREEN));
                }
                plugin.registries().freezeAll();
                // Stable harness marker: a real reload ran.
                plugin.getLogger().info("MATERIONE-RELOAD-OK items=" + summary.itemsLive());
            }
            default -> sender.sendMessage(Component.text(
                    "usage: /materione <version|doctor|reload>", NamedTextColor.RED));
        }
        return true;
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
        return List.of();
    }
}
