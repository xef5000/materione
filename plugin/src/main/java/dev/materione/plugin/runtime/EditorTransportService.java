package dev.materione.plugin.runtime;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.settings.SettingsService.SettingsState;
import dev.materione.web.BaseOps;
import dev.materione.web.EditorConfig;
import dev.materione.web.EditorListener;
import dev.materione.web.EditorSessions;
import dev.materione.web.EditorSockets;
import dev.materione.web.IdentityKeystore;
import dev.materione.web.ReloadTrigger;
import dev.materione.web.TrustPrompt;
import dev.materione.web.TrustPrompt;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.IOException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * Plugin-side owner of the direct-mode editor transport (bootstrap phase 10,
 * plugin.md §1.1): starts/stops the embedded {@link EditorListener}, answers
 * trust prompts through clickable chat, and hands out capability URLs. The
 * wire protocol itself never leaves {@code web/} (ban rule R8) — this class
 * only holds the facade.
 */
public final class EditorTransportService {

    private static final int TRUST_PROMPT_TIMEOUT_SECONDS = 120;


    private final Plugin plugin;
    private final DiagnosticsBus diagnostics;
    private final SchedulingGateway scheduling;

    private EditorListener listener;
    private EditorSessions sessions;
    private dev.materione.web.relay.RelaySessions relaySessions;
    private dev.materione.web.EditorWorkstations workstations;
    private String relayBaseUrl = "";
    private String editorMode = "cloud";
    private AutoCloseable diagnosticsSubscription;
    private dev.materione.core.document.PackWatcher packWatcher;
    private String advertiseHost = "";
    /** Trust prompts awaiting an in-game answer, keyed by nonce prefix. */
    private final Map<String, CompletableFuture<dev.materione.web.TrustPrompt.Decision>> pendingTrust =
            new ConcurrentHashMap<>();

    private final PipelineService pipeline;
    private final DeliveryService delivery;

    public EditorTransportService(Plugin plugin, DiagnosticsBus diagnostics,
            SchedulingGateway scheduling, PipelineService pipeline,
            DeliveryService delivery) {
        this.plugin = plugin;
        this.diagnostics = diagnostics;
        this.scheduling = scheduling;
        this.pipeline = pipeline;
        this.delivery = delivery;
    }

    /** Starts the listener from settings ({@code editor_*} keys). False when disabled. */
    public boolean start(SettingsState settings, dev.materione.core.storage.StorageService.StorageState storage) {
        if (!settings.getBoolean("editor_enabled", true)) {
            return false;
        }
        String host = settings.getString("editor_host", "127.0.0.1");
        int port = settings.getInt("editor_port", 8089);
        advertiseHost = settings.getString("editor_public_host", "");
        relayBaseUrl = settings.getString("relay_base_url", "https://editor.materione.com");
        editorMode = settings.getString("editor_mode", "cloud");

        KeyPair identity = new IdentityKeystore(diagnostics, new SecureRandom()).load(storage.storageDir());
        workstations = new dev.materione.web.EditorWorkstations(
                storage.storageDir().resolve("editor-workstations.json"));
        sessions = new EditorSessions(identity, this::prompt, workstations, diagnostics);
        RpcDispatcher dispatcher = new RpcDispatcher();
        BaseOps.register(dispatcher, dev.materione.plugin.MaterioneVersion.VERSION,
                () -> java.util.Map.of(
                        "editorRunning", editorRunning(),
                        "editorPort", boundPort(),
                        "editorMode", editorMode,
                        "relayBaseUrl", relayBaseUrl));
        registerContentOps(dispatcher, storage);
        relaySessions = new dev.materione.web.relay.RelaySessions(sessions, dispatcher, diagnostics);
        listener = new EditorListener(
                sessions, new EditorSockets(sessions, dispatcher, diagnostics), dispatcher, diagnostics);
        boolean bound = listener.start(new EditorConfig(host, port));
        // Relay mode needs no listener: sessions and the dispatcher live on
        // even when the local bind fails, so the cloud editor still works.
        if (!bound) {
            listener = null;
        }
        // Live diagnostics stream: every connected editor hears failures
        // as they happen instead of polling (web.md §4.2 admin feed).
        try {
            diagnosticsSubscription = diagnostics.subscribe(diagnostic -> {
                EditorSessions current = sessions;
                if (current != null) {
                    dev.materione.web.SessionPush.diagnostic(current, diagnostic);
                }
            });
        } catch (Exception e) {
            plugin.getLogger().warning("editor diagnostics stream unavailable: " + e.getMessage());
        }
        if (bound && settings.getBoolean("editor_file_watcher", true)) {
            startFileWatcher(storage);
        }
        return true;
    }

    /** True when any editor surface is up (local listener and/or relay). */
    public boolean editorRunning() {
        boolean local = listener != null && listener.isRunning();
        boolean cloud = relaySessions != null && !relaySessions.liveSessions().isEmpty();
        return local || cloud;
    }

    /**
     * External-edit feed: hand-edited YAML (and git pulls) journal an
     * external revision and reach connected editors as
     * {@code push.document.changed}, so open tabs flag the change instead of
     * clobbering on the next save. The watcher is pure JDK on its own daemon
     * thread; only the push crosses into {@code web} (R8 keeps the protocol
     * itself inside).
     */
    private void startFileWatcher(dev.materione.core.storage.StorageService.StorageState storage) {
        var documents = new dev.materione.core.document.DocumentStore(
                storage.contentPacksDir(), storage.storageDir().resolve("revisions"));
        try {
            var watcher = new dev.materione.core.document.PackWatcher(
                    storage.contentPacksDir(), documents,
                    change -> {
                        EditorSessions current = sessions;
                        if (current != null) {
                            dev.materione.web.SessionPush.documentChanged(
                                    current, change.pack(), change.path(), change.deleted());
                        }
                    });
            packWatcher = watcher;
            // the watcher never spawns threads itself (R5): runLoop blocks
            // until close, parked on an async gateway thread
            scheduling.executeAsync(plugin, watcher::runLoop);
        } catch (IOException e) {
            plugin.getLogger().warning("editor file watcher unavailable: " + e.getMessage());
        }
    }

    /**
     * Document-store ops (slice 3): browse/edit over {@code content/packs/}
     * with revisions + audit, and a targeted reload after every save — the
     * live preview loop's first half.
     */
    private void registerContentOps(RpcDispatcher dispatcher,
            dev.materione.core.storage.StorageService.StorageState storage) {
        var documents = new dev.materione.core.document.DocumentStore(
                storage.contentPacksDir(), storage.storageDir().resolve("revisions"));
        var audit = new dev.materione.core.document.AuditLog(
                storage.storageDir().resolve("audit.log"));
        ReloadTrigger trigger = new ReloadTrigger() {
            @Override
            public java.util.concurrent.CompletableFuture<Outcome> reloadFiles(
                    java.util.Set<java.nio.file.Path> files) {
                return runReload(new dev.materione.core.pipeline.ReloadScope.FileSet(files),
                        storage, false);
            }

            @Override
            public java.util.concurrent.CompletableFuture<Outcome> reloadFilesForPreview(
                    java.util.Set<java.nio.file.Path> files, String sessionId) {
                String ownerId = ownerForSession(sessionId);
                return runReload(new dev.materione.core.pipeline.ReloadScope.FileSet(files), storage, false, ownerId);
            }

            @Override
            public java.util.concurrent.CompletableFuture<Outcome> reloadScopeForPreview(
                    dev.materione.core.pipeline.ReloadScope scope, String sessionId) {
                return runReload(scope, storage, false, ownerForSession(sessionId));
            }

            private String ownerForSession(String sessionId) {
                if (sessions == null) {
                    return "";
                }
                var editorSession = sessions.find(sessionId);
                return editorSession == null ? "" : editorSession.ownerId();
            }

            @Override
            public java.util.concurrent.CompletableFuture<Outcome> reloadScope(
                    dev.materione.core.pipeline.ReloadScope scope) {
                // Manual reloads always announce, even no-ops/skips — the
                // control-center button must not fail silently.
                return runReload(scope, storage, true);
            }
        };
        dev.materione.web.rpc.ops.EditorContentOps.register(
                dispatcher, documents, audit, storage.contentPacksDir(), trigger,
                this::typeCatalog,
                () -> diagnostics.recent(100),
                this::computedMeta);
        dev.materione.web.rpc.ops.AuthoringModelOps.register(
                dispatcher, documents, storage.contentPacksDir(), audit, trigger);
        dev.materione.web.rpc.ops.AssetOps.register(
                dispatcher, storage.contentPacksDir(), audit, trigger);
        dev.materione.web.rpc.ops.AdminOps.register(
                dispatcher, audit, trigger,
                plugin.getDataFolder().toPath().resolve("settings").resolve("settings.yml"),
                dev.materione.core.settings.SettingsService::validateDraft);
        dev.materione.web.rpc.ops.DeliveryOps.register(dispatcher, new dev.materione.web.DeliveryControl() {
            @Override
            public Status status() {
                if (delivery == null) {
                    return new Status("", "");
                }
                var status = delivery.releaseStatus();
                return new Status(status.publishedSha256(), status.candidateSha256());
            }

            @Override
            public boolean publish(String expectedCandidateSha256) {
                return delivery != null && delivery.publishCandidate(expectedCandidateSha256);
            }
        });
    }

    /**
     * Server-computed metadata for the studio: a glyph's allocated codepoint.
     * The allocation table owns it (D7) — the form shows it, the file on disk
     * never contains it.
     */
    @SuppressWarnings("unused") // parsed is part of the ComputedMeta contract
    private com.google.gson.JsonObject computedMeta(
            String pack, String path, com.google.gson.JsonObject parsed) {
        if (!path.startsWith("glyphs/") || pipeline.allocation() == null || parsed == null) {
            return null;
        }
        var glyphs = new com.google.gson.JsonObject();
        for (var entry : parsed.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            dev.materione.core.content.AllocatedRange run;
            try {
                run = pipeline.allocation().allocationOf(
                        NamespacedId.parse(pack + ":" + entry.getKey()),
                        dev.materione.content.glyphs.GlyphType.ALLOCATION_KIND)
                        .map(dev.materione.core.content.AllocatedRange::parse)
                        .orElse(null);
            } catch (IllegalArgumentException malformedId) {
                continue;
            }
            if (run == null) {
                continue;
            }
            var info = new com.google.gson.JsonObject();
            info.addProperty("codepoint", dev.materione.core.text.GlyphCodepoints.of(run.start()));
            info.addProperty("count", run.count());
            info.addProperty("char", String.valueOf((char) dev.materione.core.text.GlyphCodepoints.of(run.start())));
            glyphs.add(entry.getKey(), info);
        }
        if (glyphs.size() == 0) {
            return null;
        }
        var out = new com.google.gson.JsonObject();
        out.add("glyphs", glyphs);
        return out;
    }

    /**
     * Runs any scope through the pipeline off the main thread and broadcasts
     * the outcome to every connected editor — the live preview loop's last
     * mile, shared by save-triggered and manual reloads.
     */
    java.util.concurrent.CompletableFuture<ReloadTrigger.Outcome> runReload(
            dev.materione.core.pipeline.ReloadScope scope,
            dev.materione.core.storage.StorageService.StorageState storage,
            boolean announceAlways) {
        return runReload(scope, storage, announceAlways, "", false);
    }

    java.util.concurrent.CompletableFuture<ReloadTrigger.Outcome> runReload(
            dev.materione.core.pipeline.ReloadScope scope,
            dev.materione.core.storage.StorageService.StorageState storage,
            boolean announceAlways, String previewOwnerId) {
        return runReload(scope, storage, announceAlways, previewOwnerId, true);
    }

    private java.util.concurrent.CompletableFuture<ReloadTrigger.Outcome> runReload(
            dev.materione.core.pipeline.ReloadScope scope,
            dev.materione.core.storage.StorageService.StorageState storage,
            boolean announceAlways, String previewOwnerId, boolean stageCandidate) {
        CompletableFuture<ReloadTrigger.Outcome> future = new CompletableFuture<>();
        scheduling.executeAsync(plugin, () -> {
            try {
                var summary = pipeline.reload(scope,
                        storage.contentPacksDir(), storage.generatedDir(), storage.storageDir());
                // the world datapack applies on every reload path, previews
                // included — its restart-required ledger stays honest either way
                if (plugin instanceof dev.materione.plugin.MaterionePlugin materione
                        && materione.datapackService() != null) {
                    materione.datapackService().apply(
                            summary.stagedDatapackZip(), summary.datapackSha256());
                }
                if (!summary.skipped() && summary.packSha256() != null && summary.packZip() != null && delivery != null) {
                    if (stageCandidate) {
                        delivery.stageCandidate(
                                summary.packZip(),
                                summary.packSha256(),
                                previewOwnerId == null || previewOwnerId.isBlank()
                                        ? null : java.util.UUID.fromString(previewOwnerId));
                    } else {
                        delivery.publish(summary.packZip(), summary.packSha256());
                    }
                }
                future.complete(new ReloadTrigger.Outcome(
                        !summary.skipped(), summary.noop(),
                        summary.itemsLive(), summary.quarantined()));
            } catch (Exception e) {
                // fail-soft: a broken reload never breaks the save reply
                future.complete(ReloadTrigger.Outcome.rejected());
            }
        });
        return future.whenComplete((outcome, error) -> {
            if (sessions != null && outcome != null && (announceAlways || !outcome.noop())) {
                dev.materione.web.SessionPush.reload(sessions, scopeLabel(scope), outcome.accepted(),
                        outcome.noop(), outcome.itemsLive(), outcome.quarantined());
            }
        });
    }

    /** Human-readable scope for push payloads and audit entries. */
    private static String scopeLabel(dev.materione.core.pipeline.ReloadScope scope) {
        if (scope instanceof dev.materione.core.pipeline.ReloadScope.OnePack pack) {
            return "one-pack:" + pack.packId();
        }
        return scope instanceof dev.materione.core.pipeline.ReloadScope.Everything
                ? "everything" : scope.toString();
    }

    /** Registry self-description → transport-ready schema records (plugin.md §3). */
    private java.util.List<dev.materione.web.TypeMeta> typeCatalog() {
        var out = new java.util.ArrayList<dev.materione.web.TypeMeta>();
        for (var type : pipeline.contentTypes()) {
            var description = type.describe();
            var params = new java.util.ArrayList<dev.materione.web.TypeMeta.Param>();
            for (var parameter : description.parameters()) {
                params.add(new dev.materione.web.TypeMeta.Param(
                        parameter.id(),
                        parameter.type().name().toLowerCase(java.util.Locale.ROOT),
                        parameter.required(),
                        parameter.defaultValue(),
                        parameter.description(),
                        parameter.allowedValues(),
                        java.util.List.of(),
                        parameter.uiHint(),
                        parameter.editor().label(),
                        parameter.editor().section(),
                        parameter.editor().order(),
                        parameter.editor().advanced(),
                        parameter.editor().referenceKind(),
                        parameter.editor().example()));
            }
            out.add(new dev.materione.web.TypeMeta(
                    type.name(), type.directory(),
                    description.displayName(), description.description(), params));
        }
        return out;
    }

    /** Opens a session per {@code editor_mode}: cloud relay (default) or local. */
    public void openSession(CommandSender sender) {
        if ("local".equalsIgnoreCase(editorMode)) {
            openLocalSession(sender);
            return;
        }
        openCloudSession(sender);
    }

    /** The always-available direct-mode session (power users / relay outage). */
    public void openLocalSession(CommandSender sender) {
        if (listener == null || !listener.isRunning()) {
            sender.sendMessage(Component.text(
                    "the local editor listener is not running — enable it with editor_enabled: true "
                            + "in settings.yml (or check the boot log for MTR-0030)",
                    NamedTextColor.RED));
            return;
        }
        String ownerId = sender instanceof org.bukkit.entity.Player player ? player.getUniqueId().toString() : "";
        String url = listener.openSessionUrl(advertiseHost, ownerId);
        // Stable harness marker: the harness scrapes the URL from the log.
        plugin.getLogger().info("MATERIONE-EDITOR " + url);
        sender.sendMessage(Component.text("Editor session ready (local) — open:", NamedTextColor.GREEN));
        sender.sendMessage(Component.text(url, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(url)));
        sender.sendMessage(Component.text(
                "The part after # is secret and stays in your browser. Approve the connection in game "
                        + "within " + TRUST_PROMPT_TIMEOUT_SECONDS + "s.",
                NamedTextColor.GRAY));
    }

    /**
     * The default path (web.md §4.1 over the mailbox): create the channel,
     * upload the sealed blob, print the cloud editor URL. Falls back to the
     * local listener when the relay is unreachable (MTR-0050).
     */
    public void openCloudSession(CommandSender sender) {
        if (relaySessions == null) {
            sender.sendMessage(Component.text(
                    "the editor transport never started this boot", NamedTextColor.RED));
            return;
        }
        String ownerId = sender instanceof org.bukkit.entity.Player player ? player.getUniqueId().toString() : "";
        String directHint = localDirectBase();
        try {
            var client = new dev.materione.web.relay.RelayClient(java.net.URI.create(relayBaseUrl));
            var ticket = relaySessions.open(client, relayBaseUrl, ownerId, directHint);
            scheduling.executeAsync(plugin, ticket.session()::runLoop);
            // Stable harness marker: same shape as local mode, cloud origin.
            plugin.getLogger().info("MATERIONE-EDITOR " + ticket.url());
            sender.sendMessage(Component.text("Editor session ready (cloud) — open:", NamedTextColor.GREEN));
            sender.sendMessage(Component.text(ticket.url(), NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.openUrl(ticket.url())));
            String extra = directHint == null ? ""
                    : " A local listener was detected; the editor will prefer it when reachable.";
            sender.sendMessage(Component.text(
                    "The part after # is secret and stays in your browser. Approve the connection in game "
                            + "within " + TRUST_PROMPT_TIMEOUT_SECONDS + "s." + extra,
                    NamedTextColor.GRAY));
        } catch (dev.materione.web.relay.RelayException e) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.EDITOR_RELAY_UNREACHABLE,
                    dev.materione.api.diagnostics.Severity.WARN,
                    "relay " + relayBaseUrl + " unavailable: " + e.getMessage(),
                    null, null,
                    "check relay_base_url in settings.yml, or edit via /materione editor local"));
            sender.sendMessage(Component.text(
                    "cloud editor unreachable (" + e.getMessage() + ") — trying the local listener",
                    NamedTextColor.YELLOW));
            openLocalSession(sender);
        }
    }

    /** Direct-mode base for the blob's LAN hint, or null when not listening. */
    private String localDirectBase() {
        if (listener == null || !listener.isRunning()) {
            return null;
        }
        String host = advertiseHost == null || advertiseHost.isBlank()
                ? ("0.0.0.0".equals(listener.boundHost()) ? "127.0.0.1" : listener.boundHost())
                : advertiseHost;
        return "http://" + host + ":" + listener.boundPort();
    }

    public void stop() {
        if (relaySessions != null) {
            relaySessions.stopAll();
        }
        if (packWatcher != null) {
            try {
                packWatcher.close();
            } catch (Exception e) {
                // closing is best-effort (D6)
            }
            packWatcher = null;
        }
        if (diagnosticsSubscription != null) {
            try {
                diagnosticsSubscription.close();
            } catch (Exception e) {
                // unsubscribing is best-effort (D6)
            }
            diagnosticsSubscription = null;
        }
        if (listener != null) {
            listener.stop();
            listener = null;
        }
        sessions = null;
        pendingTrust.values().forEach(future -> future.complete(TrustPrompt.Decision.DENIED));
        pendingTrust.clear();
    }

    public boolean isRunning() {
        return editorRunning();
    }

    public boolean localListenerRunning() {
        return listener != null && listener.isRunning();
    }

    public int activeEditors() {
        int local = listener == null ? 0 : listener.activeSessions();
        int cloud = relaySessions == null ? 0 : relaySessions.liveSessions().size();
        return local + cloud;
    }

    public int boundPort() {
        return listener == null ? -1 : listener.boundPort();
    }

    /** In-game approval of one pending hello (command: {@code editor approve <prefix> [trust]}). */
    public void approve(CommandSender sender, String noncePrefix, boolean trustWorkstation) {
        resolve(sender, noncePrefix, trustWorkstation
                ? TrustPrompt.Decision.APPROVED_AND_TRUST : TrustPrompt.Decision.APPROVED);
    }

    /** In-game denial of one pending hello. */
    public void deny(CommandSender sender, String noncePrefix) {
        resolve(sender, noncePrefix, TrustPrompt.Decision.DENIED);
    }

    /** Closes every live session and pending prompt (no listener restart needed). */
    public void revokeAll(CommandSender sender) {
        if (relaySessions != null) {
            relaySessions.stopAll();
        }
        if (sessions != null) {
            sessions.closeAll();
        }
        pendingTrust.values().forEach(future -> future.complete(TrustPrompt.Decision.DENIED));
        pendingTrust.clear();
        sender.sendMessage(Component.text("all editor sessions revoked", NamedTextColor.GREEN));
    }

    /** Revokes trusted workstations: one by key-hash prefix, or all with "workstations". */
    public void revokeWorkstations(CommandSender sender, String selector) {
        if (workstations == null) {
            sender.sendMessage(Component.text("the editor transport never started this boot",
                    NamedTextColor.RED));
            return;
        }
        if (selector.equalsIgnoreCase("workstations")) {
            workstations.revokeAll();
            sender.sendMessage(Component.text("all trusted workstations revoked", NamedTextColor.GREEN));
            return;
        }
        sender.sendMessage(workstations.revoke(selector)
                ? Component.text("trusted workstation " + selector + "… revoked", NamedTextColor.GREEN)
                : Component.text("no trusted workstation starts with " + selector, NamedTextColor.RED));
    }

    // ------------------------------------------------------------------

    /**
     * The trust decision point: broadcasts an actionable prompt to every
     * admin online plus the console, and completes when someone answers.
     */
    private CompletableFuture<TrustPrompt.Decision> prompt(String noncePrefix, String browser) {
        CompletableFuture<TrustPrompt.Decision> future = new CompletableFuture<>();
        CompletableFuture<TrustPrompt.Decision> previous = pendingTrust.put(noncePrefix, future);
        if (previous != null) {
            previous.complete(TrustPrompt.Decision.DENIED); // prefix collision: the older loses
        }
        future.whenComplete((decision, error) -> pendingTrust.remove(noncePrefix, future));

        Component prompt = Component.text("[Materione] ", NamedTextColor.GOLD)
                .append(Component.text("'" + browser + "' wants to connect as editor", NamedTextColor.YELLOW))
                .append(Component.text(" (nonce " + noncePrefix + ") ", NamedTextColor.AQUA))
                .append(Component.text("[Approve]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/materione editor approve " + noncePrefix)))
                .append(Component.text(" "))
                .append(Component.text("[Approve & trust this browser]", NamedTextColor.DARK_GREEN)
                        .clickEvent(ClickEvent.runCommand(
                                "/materione editor approve " + noncePrefix + " trust")))
                .append(Component.text(" "))
                .append(Component.text("[Deny]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/materione editor deny " + noncePrefix)));
        scheduling.executeGlobal(plugin, () -> {
            plugin.getServer().getConsoleSender().sendMessage(prompt);
            plugin.getServer().getOnlinePlayers().forEach(player -> {
                if (player.hasPermission("materione.admin")) {
                    player.sendMessage(prompt);
                }
            });
        });
        return future;
    }

    private void resolve(CommandSender sender, String noncePrefix, TrustPrompt.Decision decision) {
        if (!sender.hasPermission("materione.admin")) {
            sender.sendMessage(Component.text("no permission (materione.admin)", NamedTextColor.RED));
            return;
        }
        CompletableFuture<TrustPrompt.Decision> future = pendingTrust.get(noncePrefix);
        if (future == null || future.isDone()) {
            sender.sendMessage(Component.text(
                    "no pending editor request with nonce " + noncePrefix, NamedTextColor.RED));
            return;
        }
        future.complete(decision);
        sender.sendMessage(Component.text(
                (decision.approved() ? "approved" : "denied") + " editor request " + noncePrefix
                        + (decision.trustWorkstation() ? " (browser trusted for next time)" : ""),
                decision.approved() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    }
}
