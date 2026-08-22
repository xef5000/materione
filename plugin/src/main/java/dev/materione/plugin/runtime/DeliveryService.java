package dev.materione.plugin.runtime;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.delivery.PackDeliveryBackend;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.settings.SettingsService.SettingsState;
import dev.materione.delivery.SelfHostBackend;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

/**
 * Pack delivery (plugin.md §4.6): publishes the staged pack through the
 * configured backend and sends it to players — on join, and to everyone
 * online after a pack-changing apply. Delivery failure never rolls back
 * apply (the server runs the new content; players keep the old pack), and
 * every send/status is observable (MTR codes, {@code /materione doctor}).
 */
public final class DeliveryService implements Listener {

    private final Plugin plugin;
    private final DiagnosticsBus diagnostics;
    private final SchedulingGateway scheduling;
    private PackDeliveryBackend backend;
    private volatile String currentUrl = "";
    private volatile byte[] currentSha1 = new byte[0];
    private volatile int declined;

    public DeliveryService(Plugin plugin, DiagnosticsBus diagnostics, SchedulingGateway scheduling) {
        this.plugin = plugin;
        this.diagnostics = diagnostics;
        this.scheduling = scheduling;
    }

    /** Starts the self-host backend (M1's only backend; more via settings in M7). */
    public void start(SettingsState settings) {
        String bindHost = settings.getString("delivery_host", "0.0.0.0");
        int port = settings.getInt("delivery_port", 8090);
        String publicHost = settings.getString("delivery_public_host", "");
        if (publicHost.isBlank()) {
            publicHost = bindHost.equals("0.0.0.0") ? "localhost" : bindHost;
        }
        try {
            backend = new SelfHostBackend(bindHost, port, publicHost);
        } catch (IOException e) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.DELIVERY_FAILED, Severity.ERROR,
                    "self-host backend failed to start on " + bindHost + ":" + port + ": " + e.getMessage(),
                    null, null, "check the port (delivery_port) — content still applies; "
                            + "players keep whatever pack they already have"));
        }
    }

    /** Publishes a newly compiled pack and resends to everyone online. */
    public void publish(Path packZip, String sha256) {
        if (backend == null) {
            return;
        }
        try {
            currentUrl = backend.publish(packZip, sha256);
            currentSha1 = java.util.HexFormat.of()
                    .parseHex(SelfHostBackend.sha1Of(Files.readAllBytes(packZip)));
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.DELIVERY_PUBLISHED, Severity.INFO, "pack published at " + currentUrl,
                    null, null, null));
        } catch (IOException e) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.DELIVERY_FAILED, Severity.ERROR,
                    "publish failed: " + e.getMessage(),
                    null, null, "apply is NOT rolled back; fix delivery and run "
                            + "/materione reload to retry publishing"));
            return;
        }
        resendToOnline();
    }

    /** The URL players should fetch (empty before the first publish). */
    public String currentUrl() {
        return currentUrl;
    }

    public int declinedCount() {
        return declined;
    }

    public boolean isRunning() {
        return backend != null;
    }

    void resendToOnline() {
        if (currentUrl.isEmpty() || currentSha1.length == 0) {
            return;
        }
        scheduling.executeGlobal(plugin, () ->
                plugin.getServer().getOnlinePlayers().forEach(this::send));
    }

    /** The 1.21.4 floor's send API (Paper's ResourcePack/sendResourcePacks is newer). */
    @SuppressWarnings("deprecation")
    void send(Player player) {
        if (currentUrl.isEmpty() || currentSha1.length == 0) {
            return;
        }
        player.setResourcePack(currentUrl, currentSha1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        send(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        var status = event.getStatus();
        if (status == PlayerResourcePackStatusEvent.Status.DECLINED
                || status == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
            declined++;
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.PACK_DECLINED, Severity.WARN,
                    event.getPlayer().getName() + ": " + status,
                    null, null, "the server keeps running the new content; the player "
                            + "sees the old pack (or none) until they accept"));
        }
    }

    public void stop() {
        if (backend != null) {
            backend.close();
            backend = null;
        }
    }
}
