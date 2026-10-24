package dev.materione.plugin.runtime.api;

import dev.materione.api.ApiResult;
import dev.materione.api.admin.AdminApi;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.platform.ProbeResult;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.Documents;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.settings.SettingsService;
import dev.materione.core.storage.RestartStaging;
import dev.materione.plugin.runtime.DeliveryService;
import dev.materione.plugin.runtime.EditorTransportService;
import dev.materione.plugin.runtime.PipelineService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * The {@link AdminApi} implementation: the structured doctor report (one
 * source for {@code /materione doctor} and the web dashboard), hash-guarded
 * pre-validated settings writes (invalid drafts never reach the disk), the
 * audit tail, and the editor session control surface bridged from the
 * transport service.
 */
public final class AdminApiImpl implements AdminApi {

    private static final Object SETTINGS_LOCK = new Object();

    private final Supplier<ProbeResult> probe;
    private final RegistryService registries;
    private final DiagnosticsBus diagnostics;
    private final QuarantineTracker quarantine;
    private final Supplier<PipelineService> pipeline;
    private final Supplier<RestartStaging> restartStaging;
    private final Supplier<DeliveryService> delivery;
    private final Path settingsFile;
    private final AuditLog audit;
    private final Supplier<EditorTransportService> editorTransport;
    private final SettingsService settingsService;
    private final Supplier<dev.materione.core.settings.SettingsService.SettingsState> settingsState;
    private final Supplier<dev.materione.core.storage.StorageService.StorageState> storageState;
    private final Supplier<dev.materione.plugin.runtime.DatapackService> datapack;

    public AdminApiImpl(
            Supplier<ProbeResult> probe,
            RegistryService registries,
            DiagnosticsBus diagnostics,
            QuarantineTracker quarantine,
            Supplier<PipelineService> pipeline,
            Supplier<RestartStaging> restartStaging,
            Supplier<DeliveryService> delivery,
            Path settingsDir,
            AuditLog audit,
            Supplier<EditorTransportService> editorTransport,
            SettingsService settingsService,
            Supplier<dev.materione.core.settings.SettingsService.SettingsState> settingsState,
            Supplier<dev.materione.core.storage.StorageService.StorageState> storageState,
            Supplier<dev.materione.plugin.runtime.DatapackService> datapack) {
        this.probe = Objects.requireNonNull(probe, "probe");
        this.registries = registries;
        this.diagnostics = diagnostics;
        this.quarantine = quarantine;
        this.pipeline = pipeline;
        this.restartStaging = restartStaging;
        this.delivery = delivery;
        this.settingsFile = settingsDir.resolve("settings.yml");
        this.audit = audit;
        this.editorTransport = editorTransport;
        this.settingsService = settingsService;
        this.settingsState = settingsState;
        this.storageState = storageState;
        this.datapack = datapack;
    }

    @Override
    public DoctorReport doctor() {
        String unsupportedReason = null;
        dev.materione.api.platform.PlatformDescriptor descriptor = null;
        if (probe.get() instanceof ProbeResult.Supported supported) {
            descriptor = supported.descriptor();
        } else if (probe.get() instanceof ProbeResult.Unsupported unsupported) {
            unsupportedReason = unsupported.reason();
        }

        PipelineService service = pipeline.get();
        int liveItems = -1;
        int liveBlocks = -1;
        int liveFurniture = -1;
        int liveRecipes = -1;
        String packSha256 = null;
        if (service != null && service.runtime() != null) {
            var registriesView = service.runtime().registries();
            liveItems = registriesView.itemCount();
            liveRecipes = registriesView.recipes().size();
            var blocks = registriesView.view(dev.materione.core.runtime.RuntimeBlocks.KEY);
            var furnitures = registriesView.view(dev.materione.core.runtime.RuntimeFurnitures.KEY);
            liveBlocks = blocks == null ? 0 : blocks.ids().size();
            liveFurniture = furnitures == null ? 0 : furnitures.all().size();
            String sha = service.runtime().livePackSha256();
            packSha256 = sha == null || sha.isBlank() ? null : sha;
        }

        int allocationLive = -1;
        boolean allocationCorrupt = false;
        if (service != null && service.allocation() != null) {
            allocationLive = service.allocation().liveCount();
            allocationCorrupt = service.allocation().isCorrupt();
        }

        RestartStaging staging = restartStaging.get();
        int pendingRestart = staging == null ? -1 : staging.load().size();

        DeliveryService deliveryService = delivery.get();
        dev.materione.api.delivery.DeliveryApi.DeliveryStatus deliveryStatus =
                new dev.materione.api.delivery.DeliveryApi.DeliveryStatus(
                        deliveryService == null ? null : deliveryService.releaseStatus().publishedSha256(),
                        deliveryService == null ? null : deliveryService.releaseStatus().candidateSha256(),
                        deliveryService == null || !deliveryService.isRunning()
                                ? null
                                : deliveryService.currentUrl(),
                        deliveryService != null && deliveryService.isRunning());

        List<String> registryLines = registries == null ? List.of() : registries.doctorLines();
        List<Diagnostic> recent = new ArrayList<>(diagnostics.recent(32));
        recent.sort((a, b) -> {
            int left = a.severity() == Severity.ERROR ? 0 : a.severity() == Severity.WARN ? 1 : 2;
            int right = b.severity() == Severity.ERROR ? 0 : b.severity() == Severity.WARN ? 1 : 2;
            return Integer.compare(left, right);
        });

        var settings = settingsState.get();
        var storage = storageState.get();
        dev.materione.plugin.runtime.DatapackService datapackService = datapack.get();

        List<String> acceptance = new ArrayList<>();
        if (deliveryService != null && !deliveryService.acceptanceSnapshot().isEmpty()) {
            deliveryService.acceptanceSnapshot().forEach((id, state) -> {
                var player = org.bukkit.Bukkit.getPlayer(id);
                acceptance.add((player != null ? player.getName() : id.toString().substring(0, 8))
                        + "=" + state.name().toLowerCase(java.util.Locale.ROOT));
            });
            java.util.Collections.sort(acceptance);
        }

        return new DoctorReport(
                descriptor,
                unsupportedReason,
                liveItems,
                liveBlocks,
                liveFurniture,
                liveRecipes,
                packSha256,
                quarantine == null ? -1 : quarantine.size(),
                allocationLive,
                allocationCorrupt,
                pendingRestart,
                service == null ? null : service.lastReport(),
                registryLines,
                deliveryStatus,
                recent,
                settings == null ? null : settings.version(),
                settings == null ? 0 : settings.diagnostics().size(),
                storage == null ? null : storage.formatVersion(),
                acceptance,
                diagnostics.count(Severity.ERROR),
                diagnostics.count(Severity.WARN),
                diagnostics.count(Severity.INFO),
                datapackSummary(datapackService),
                datapackService == null ? null : datapackService.isRegistryMode(),
                datapackService == null ? null : datapackService.pendingRestartRequired(),
                datapackService == null ? null : datapackService.isAcceptedByServer(),
                registries == null ? -1 : registries.registryCount(),
                registries == null ? -1 : registries.totalEntries());
    }

    /** The registry-vs-datapack status line, rendered exactly as doctor shows it. */
    private static String datapackSummary(dev.materione.plugin.runtime.DatapackService datapack) {
        if (datapack == null) {
            return null;
        }
        if (datapack.isRegistryMode()) {
            return "registry: paintings/jukebox registered at boot (content "
                    + datapack.registeredHashOrNone() + ")";
        }
        return "datapack: deployed " + datapack.deployedHashOrNone()
                + ", loaded at boot " + datapack.activeAtBootOrNone();
    }

    @Override
    public ApiResult<SettingsView> getSettings(ActorContext actor) {
        requirePermission(actor, "materione.admin");
        try {
            if (!Files.isRegularFile(settingsFile)) {
                return ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE,
                        "settings file does not exist yet");
            }
            String raw = Files.readString(settingsFile, StandardCharsets.UTF_8);
            return ApiResult.ok(new SettingsView(raw, Documents.sha256(raw), parsedView(raw)));
        } catch (IOException e) {
            return ApiResult.fail(DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "settings read failed: " + e.getMessage());
        }
    }

    @Override
    public ApiResult<Void> putSettings(ActorContext actor, String content, String expectedSha256) {
        requirePermission(actor, "materione.admin");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        // pre-validated: an invalid draft never touches the disk
        List<Diagnostic> problems = SettingsService.validateDraft(content);
        if (!problems.isEmpty()) {
            return ApiResult.fail(problems);
        }
        synchronized (SETTINGS_LOCK) {
            try {
                String current = Files.isRegularFile(settingsFile)
                        ? Files.readString(settingsFile, StandardCharsets.UTF_8) : "";
                if (!Documents.sha256(current).equals(expectedSha256)) {
                    return ApiResult.fail(new Diagnostic(
                            DiagnosticCodes.EDITOR_DOCUMENT_CONFLICT, Severity.WARN,
                            "settings changed elsewhere since you loaded them",
                            null, null, "reload the settings and retry"));
                }
                atomicWrite(settingsFile, content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return ApiResult.fail(DiagnosticCodes.PIPELINE_STAGE_FAILED,
                        "settings write failed: " + e.getMessage());
            }
        }
        audit.record(actor.actorId(), "admin.settings.put", "settings.yml");
        return ApiResult.ok(null);
    }

    @Override
    public List<AuditEntry> recentAudit(int limit) {
        List<AuditEntry> out = new ArrayList<>();
        if (audit == null) {
            return out;
        }
        for (AuditLog.Entry entry : audit.recent(limit)) {
            out.add(new AuditEntry(entry.ts(), entry.actor(), entry.op(), entry.target()));
        }
        return out;
    }

    @Override
    public EditorSessionControl editorSessions() {
        return new EditorSessions();
    }

    // ------------------------------------------------------------------

    private final class EditorSessions implements EditorSessionControl {

        @Override
        public ApiResult<String> openSession(ActorContext actor) {
            return openForMode(actor, transport -> transport.openSession(senderOf(actor)));
        }

        @Override
        public ApiResult<String> openLocalSession(ActorContext actor) {
            return openForMode(actor, transport -> transport.openLocalSession(senderOf(actor)));
        }

        private ApiResult<String> openForMode(ActorContext actor, java.util.function.Function<
                EditorTransportService, String> opener) {
            requirePermission(actor, "materione.editor");
            EditorTransportService transport = editorTransport.get();
            if (transport == null) {
                return ApiResult.fail(DiagnosticCodes.PIPELINE_STAGE_FAILED,
                        "editor transport is not running");
            }
            String url = opener.apply(transport);
            return url == null
                    ? ApiResult.fail(DiagnosticCodes.EDITOR_LISTENER_FAILED,
                            "no editor surface is available (listener down and relay unreachable)")
                    : ApiResult.ok(url);
        }

        @Override
        public EditorListenerStatus status() {
            EditorTransportService transport = editorTransport.get();
            if (transport == null) {
                return new EditorListenerStatus(false, false, -1, 0, "cloud");
            }
            return new EditorListenerStatus(
                    transport.isRunning(),
                    transport.localListenerRunning(),
                    transport.boundPort(),
                    transport.activeEditors(),
                    transport.editorMode());
        }

        @Override
        public ApiResult<Void> approve(ActorContext actor, String noncePrefix) {
            return approve(actor, noncePrefix, false);
        }

        @Override
        public ApiResult<Void> approve(ActorContext actor, String noncePrefix, boolean trustWorkstation) {
            return sessionMutation(actor, "materione.admin", noncePrefix,
                    (transport, sender) -> transport.approve(sender, noncePrefix, trustWorkstation));
        }

        @Override
        public ApiResult<Void> deny(ActorContext actor, String noncePrefix) {
            return sessionMutation(actor, "materione.admin", noncePrefix,
                    (transport, sender) -> transport.deny(sender, noncePrefix));
        }

        @Override
        public ApiResult<Void> revokeAll(ActorContext actor) {
            return sessionMutation(actor, "materione.admin", null,
                    (transport, sender) -> transport.revokeAll(sender));
        }

        @Override
        public ApiResult<Void> revokeWorkstations(ActorContext actor, String selector) {
            return sessionMutation(actor, "materione.admin", null,
                    (transport, sender) -> transport.revokeWorkstations(sender, selector));
        }

        @Override
        public ApiResult<Void> stopListener(ActorContext actor) {
            return sessionMutation(actor, "materione.admin", null,
                    (transport, sender) -> transport.stop());
        }
    }

    private interface TransportMutation {
        void apply(EditorTransportService transport, CommandSender sender);
    }

    private ApiResult<Void> sessionMutation(
            ActorContext actor, String permission, String nonce, TransportMutation mutation) {
        requirePermission(actor, permission);
        EditorTransportService transport = editorTransport.get();
        if (transport == null) {
            return ApiResult.fail(DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "editor transport is not running");
        }
        mutation.apply(transport, senderOf(actor));
        audit.record(actor.actorId(), "editor." + (nonce == null ? "session" : "trust"),
                nonce == null ? "editor-transport" : nonce);
        return ApiResult.ok(null);
    }

    /** Resolves the Bukkit sender an actor binds to (player or console). */
    private static CommandSender senderOf(ActorContext actor) {
        UUID player = actor == null ? null : actor.playerUuid().orElse(null);
        if (player != null) {
            org.bukkit.entity.Player online = Bukkit.getPlayer(player);
            if (online != null) {
                return online;
            }
        }
        return Bukkit.getConsoleSender();
    }

    private static void requirePermission(ActorContext actor, String node) {
        if (actor == null || !actor.hasPermission(node)) {
            throw new IllegalArgumentException("no permission (" + node + ")");
        }
    }

    /** Atomic settings write (temp file + move), mirroring the wire op's behavior. */
    private static void atomicWrite(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp-" + System.nanoTime());
        Files.write(temp, bytes);
        try {
            Files.move(temp, file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The data view for the settings form pre-fill (plain maps/lists/scalars). */
    private static java.util.Map<String, Object> parsedView(String raw) {
        try {
            var parsed = dev.materione.core.document.LosslessYaml.parse(raw);
            return parsed.parseError().isEmpty()
                    ? dev.materione.core.document.LosslessYaml.stringKeyed(parsed.data())
                    : java.util.Map.of();
        } catch (RuntimeException e) {
            return java.util.Map.of();
        }
    }
}
