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
    private final Path stateDir;
    private PackDeliveryBackend backend;
    private volatile String currentUrl = "";
    private volatile byte[] currentSha1 = new byte[0];
    private volatile String publishedSha256 = "";
    private volatile String candidateUrl = "";
    private volatile byte[] candidateSha1 = new byte[0];
    private volatile String candidateSha256 = "";
    private volatile byte[] candidateBytes = new byte[0];
    private volatile int declined;

    public DeliveryService(Plugin plugin, DiagnosticsBus diagnostics, SchedulingGateway scheduling) {
        this(plugin, diagnostics, scheduling, null);
    }

    public DeliveryService(
            Plugin plugin,
            DiagnosticsBus diagnostics,
            SchedulingGateway scheduling,
            Path stateDir) {
        this.plugin = plugin;
        this.diagnostics = diagnostics;
        this.scheduling = scheduling;
        this.stateDir = stateDir;
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
        } catch (IOException primaryFailure) {
            // configured port busy — typical when several Paper servers run on
            // one machine. Walk a few nearby ports: the published URL carries
            // the actually-bound port (SelfHostBackend), so delivery stays
            // correct wherever we land.
            for (int candidate = port + 1; candidate <= port + 8 && backend == null; candidate++) {
                try {
                    backend = new SelfHostBackend(bindHost, candidate, publicHost);
                    diagnostics.publish(new Diagnostic(
                            DiagnosticCodes.DELIVERY_FAILED, Severity.WARN,
                            "configured delivery port " + port + " is busy ("
                                    + primaryFailure.getMessage() + "); self-host backend "
                                    + "bound to " + candidate + " instead — the published "
                                    + "pack URL reflects it",
                            null, null, "set delivery_port in settings.yml to pin a port"));
                } catch (IOException ignored) {
                    // keep walking
                }
            }
        }
        if (backend == null) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.DELIVERY_FAILED, Severity.ERROR,
                    "self-host backend failed to start on " + bindHost + ":" + port
                            + " (and " + port + "+1.." + (port + 8) + "): no free port",
                    null, null, "check the port (delivery_port) — content still applies; "
                            + "players keep whatever pack they already have"));
        }
        restorePublished();
    }

    /** Publishes a newly compiled pack and resends to everyone online. */
    public synchronized void publish(Path packZip, String sha256) {
        if (backend == null) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(packZip);
            String nextUrl = backend.publish(packZip, sha256);
            byte[] nextSha1 = java.util.HexFormat.of()
                    .parseHex(SelfHostBackend.sha1Of(bytes));
            if (!persistPublished(bytes, sha256)) {
                return;
            }
            if (!currentUrl.isEmpty()) {
                backend.unpin(currentUrl);
            }
            clearCandidate();
            currentUrl = nextUrl;
            backend.pin(currentUrl);
            currentSha1 = nextSha1;
            publishedSha256 = sha256;
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

    /**
     * Hosts a successfully compiled editor candidate and sends it only to the
     * player who opened that editor session.
     */
    public synchronized void stageCandidate(Path packZip, String sha256, java.util.UUID ownerId) {
        if (backend == null) {
            return;
        }
        try {
            if (!candidateUrl.isEmpty()) {
                backend.unpin(candidateUrl);
            }
            candidateUrl = backend.publish(packZip, sha256);
            backend.pin(candidateUrl);
            candidateSha1 = java.util.HexFormat.of()
                    .parseHex(SelfHostBackend.sha1Of(Files.readAllBytes(packZip)));
            candidateSha256 = sha256;
            candidateBytes = Files.readAllBytes(packZip);
        } catch (IOException e) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.DELIVERY_FAILED, Severity.ERROR,
                    "candidate publish failed: " + e.getMessage(),
                    null, null, "runtime changes are live; fix delivery and save again"));
            return;
        }
        if (ownerId != null) {
            String previewUrl = candidateUrl;
            byte[] previewSha1 = candidateSha1.clone();
            scheduling.executeGlobal(plugin, () -> {
                Player player = plugin.getServer().getPlayer(ownerId);
                if (player != null) {
                    send(player, previewUrl, previewSha1);
                }
            });
        }
    }

    /** Promotes only the exact candidate the editor claims to have previewed. */
    public synchronized boolean publishCandidate(String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isBlank()
                || !expectedSha256.equals(candidateSha256)
                || candidateUrl.isEmpty() || candidateSha1.length == 0) {
            return false;
        }
        if (!persistPublished(candidateBytes, candidateSha256)) {
            return false;
        }
        if (!currentUrl.isEmpty()) {
            backend.unpin(currentUrl);
        }
        currentUrl = candidateUrl;
        currentSha1 = candidateSha1.clone();
        publishedSha256 = candidateSha256;
        candidateUrl = "";
        candidateSha1 = new byte[0];
        candidateSha256 = "";
        candidateBytes = new byte[0];
        diagnostics.publish(new Diagnostic(
                DiagnosticCodes.DELIVERY_PUBLISHED, Severity.INFO,
                "candidate " + publishedSha256 + " promoted to all players",
                null, null, null));
        resendToOnline();
        return true;
    }

    public ReleaseStatus releaseStatus() {
        return new ReleaseStatus(publishedSha256, candidateSha256);
    }

    public boolean hasPublishedVersion() {
        return !publishedSha256.isBlank() && !currentUrl.isBlank();
    }

    public record ReleaseStatus(String publishedSha256, String candidateSha256) {}

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
        if (currentUrl.isEmpty() || currentSha1.length == 0 || plugin == null || plugin.getServer() == null) {
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

    @SuppressWarnings("deprecation")
    private static void send(Player player, String url, byte[] sha1) {
        player.setResourcePack(url, sha1);
    }

    private void restorePublished() {
        if (backend == null || stateDir == null) {
            return;
        }
        Path hash = stateDir.resolve("delivery").resolve("published.sha256");
        if (!Files.isRegularFile(hash)) {
            return;
        }
        try {
            String sha256 = Files.readString(hash).trim();
            Path zip = stateDir.resolve("delivery").resolve("versions").resolve(sha256 + ".zip");
            if (!Files.isRegularFile(zip)) {
                throw new IOException("published snapshot is missing");
            }
            byte[] bytes = Files.readAllBytes(zip);
            String actualSha256 = sha256(bytes);
            if (!sha256.equalsIgnoreCase(actualSha256)) {
                throw new IOException("published pack hash does not match its snapshot");
            }
            currentUrl = backend.publish(zip, sha256);
            backend.pin(currentUrl);
            currentSha1 = java.util.HexFormat.of()
                    .parseHex(SelfHostBackend.sha1Of(bytes));
            publishedSha256 = sha256;
        } catch (Exception e) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.DELIVERY_FAILED, Severity.WARN,
                    "could not restore published pack: " + e.getMessage(),
                    null, null, "the next explicit publish will repair delivery state"));
        }
    }

    private boolean persistPublished(byte[] bytes, String sha256) {
        if (stateDir == null) {
            return true;
        }
        try {
            Path dir = stateDir.resolve("delivery");
            Path versions = dir.resolve("versions");
            Files.createDirectories(versions);
            Path snapshot = versions.resolve(sha256 + ".zip");
            Path zipTemp = versions.resolve(sha256 + ".zip.tmp");
            Path hashTemp = dir.resolve("published.sha256.tmp");
            if (!Files.isRegularFile(snapshot)) {
                Files.write(zipTemp, bytes);
                move(zipTemp, snapshot);
            }
            Files.writeString(hashTemp, sha256 + "\n");
            move(hashTemp, dir.resolve("published.sha256"));
            pruneSnapshots(versions, sha256);
            return true;
        } catch (IOException e) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.DELIVERY_FAILED, Severity.ERROR,
                    "could not persist published pack: " + e.getMessage(),
                    null, null, "nothing was promoted; fix storage and publish again"));
            return false;
        }
    }

    private void clearCandidate() {
        if (!candidateUrl.isEmpty()) {
            backend.unpin(candidateUrl);
        }
        candidateUrl = "";
        candidateSha1 = new byte[0];
        candidateSha256 = "";
        candidateBytes = new byte[0];
    }

    private static void pruneSnapshots(Path versions, String publishedSha256) throws IOException {
        try (var files = Files.list(versions)) {
            var snapshots = files.filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .filter(path -> !path.getFileName().toString().equals(publishedSha256 + ".zip"))
                    .sorted(java.util.Comparator.comparingLong(DeliveryService::lastModified))
                    .toList();
            for (int i = 0; i < snapshots.size() - 3; i++) {
                Files.deleteIfExists(snapshots.get(i));
            }
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
