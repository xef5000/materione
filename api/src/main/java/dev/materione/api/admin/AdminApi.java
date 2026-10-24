package dev.materione.api.admin;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.delivery.DeliveryApi.DeliveryStatus;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.pipeline.ApplyReport;
import dev.materione.api.platform.PlatformDescriptor;
import java.util.List;

/**
 * Server health, settings, audit and editor-session control (api.md §3.8,
 * plugin.md §5/§7.1). {@link #doctor()} returns the structured record both
 * {@code /materione doctor} and the web dashboard render — one source, two
 * presentations. Settings writes are hash-guarded and pre-validated: invalid
 * drafts never reach the disk (D6's spirit applied to configuration).
 */
public interface AdminApi {

    /** The structured doctor report (platform, live counts, quarantine, delivery, recent diagnostics). */
    DoctorReport doctor();

    /** settings.yml as written on disk, with its content hash for guarded writes. */
    ApiResult<SettingsView> getSettings(ActorContext actor);

    /**
     * Hash-guarded, pre-validated settings write. A stale {@code
     * expectedSha256} or a draft that fails validation ({@code MTR-00})
     * returns diagnostics without touching the file.
     */
    ApiResult<Void> putSettings(ActorContext actor, String content, String expectedSha256);

    /** The tail of {@code storage/audit.log}. */
    List<AuditEntry> recentAudit(int limit);

    /** The editor session/listener surface ({@code /materione editor …}). */
    EditorSessionControl editorSessions();

    // ------------------------------------------------------------------

    /**
     * One health snapshot. Counts reflect the last successful apply; counts
     * of -1 mean "subsystem not initialized" (e.g. pipeline disabled).
     *
     * @param platform          the resolved platform descriptor, null when the probe failed
     * @param unsupportedReason the probe's reason when platform is null
     * @param liveItems         live items after the last apply
     * @param liveBlocks        live custom blocks
     * @param liveFurniture     live furniture
     * @param liveRecipes       live recipes
     * @param packSha256        the live pack hash, null before the first apply
     * @param quarantinedCount  currently quarantined resources
     * @param allocationLiveCount entries in the durable allocation table, -1 when not loaded
     * @param allocationCorrupt whether the allocation table flagged corruption
     * @param pendingRestartCount changes staged for the next boot
     * @param lastApply         the last pre-apply report, null before the first apply
     * @param registryLines     per-registry health lines (the registry self-report)
     * @param delivery          the release-channel status
     * @param recentDiagnostics recent diagnostics from the bus, most severe first
     */
    record DoctorReport(
            PlatformDescriptor platform,
            String unsupportedReason,
            int liveItems,
            int liveBlocks,
            int liveFurniture,
            int liveRecipes,
            String packSha256,
            int quarantinedCount,
            int allocationLiveCount,
            boolean allocationCorrupt,
            int pendingRestartCount,
            ApplyReport lastApply,
            List<String> registryLines,
            DeliveryStatus delivery,
            List<Diagnostic> recentDiagnostics,
            Integer settingsVersion,
            int settingsLoadDiagnostics,
            Integer storageFormatVersion,
            List<String> acceptanceLines,
            long errorCount,
            long warnCount,
            long infoCount,
            String datapackSummary,
            Boolean datapackRegistryMode,
            Boolean datapackPendingRestart,
            Boolean datapackAccepted,
            int registryCount,
            int registryEntryCount) {}

    /** settings.yml content, its sha256, and the parsed view for form pre-fill. */
    record SettingsView(String content, String sha256, java.util.Map<String, Object> parsed) {}

    /** One audit log entry. */
    record AuditEntry(String timestamp, String actor, String operation, String target) {}

    /** The editor transport control surface. */
    interface EditorSessionControl {

        /**
         * Opens a session per {@code editor_mode} (cloud relay by default,
         * local per settings) and returns the capability URL (in-game trust
         * flow, master §9.2).
         */
        ApiResult<String> openSession(ActorContext actor);

        /**
         * Opens a direct-mode (local listener) session regardless of
         * {@code editor_mode} — the always-available surface.
         */
        ApiResult<String> openLocalSession(ActorContext actor);

        EditorListenerStatus status();

        ApiResult<Void> approve(ActorContext actor, String noncePrefix);

        /** Approves with an optional trusted-workstation flag (web.md §5.2). */
        ApiResult<Void> approve(ActorContext actor, String noncePrefix, boolean trustWorkstation);

        ApiResult<Void> deny(ActorContext actor, String noncePrefix);

        ApiResult<Void> revokeAll(ActorContext actor);

        /** Revokes trusted workstations: one key-hash prefix, or all with "workstations". */
        ApiResult<Void> revokeWorkstations(ActorContext actor, String selector);

        /** Stops the embedded listener (direct mode). */
        ApiResult<Void> stopListener(ActorContext actor);
    }

    /**
     * Editor transport health: any surface up ({@code running}), the local
     * listener specifically, and the configured mode.
     */
    record EditorListenerStatus(
            boolean running, boolean localListener, int boundPort, int activeSessions, String editorMode) {

        /** Compatibility shape for callers that only check liveness. */
        public EditorListenerStatus(boolean running, int boundPort, int activeSessions) {
            this(running, false, boundPort, activeSessions, "cloud");
        }
    }
}
