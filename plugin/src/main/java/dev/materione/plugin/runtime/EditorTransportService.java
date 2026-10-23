package dev.materione.plugin.runtime;

import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.pipeline.PipelineApi;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.authoring.AuthoringApiImpl;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.settings.SettingsService.SettingsState;
import dev.materione.plugin.runtime.api.GlyphMetadataComputer;
import dev.materione.web.BaseOps;
import dev.materione.web.EditorConfig;
import dev.materione.web.EditorListener;
import dev.materione.web.EditorSessions;
import dev.materione.web.EditorSockets;
import dev.materione.web.IdentityKeystore;
import dev.materione.web.ReloadTrigger;
import dev.materione.web.TrustPrompt;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.IOException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 *
 * <p>Since the API-first transformation, reloads delegate to the plugin's
 * {@link PipelineApi} implementation — one reload path for the editor, the
 * commands and future MCP callers — and the file-watcher feed also reaches
 * the authoring API's change subscription.
 */
public final class EditorTransportService {

    private static final int TRUST_PROMPT_TIMEOUT_SECONDS = 120;


    private final Plugin plugin;
    private final DiagnosticsBus diagnostics;
    private final SchedulingGateway scheduling;

    private EditorListener listener;
    private EditorSessions sessions;
    private AutoCloseable diagnosticsSubscription;
    private dev.materione.core.document.PackWatcher packWatcher;
    private String advertiseHost = "";
    /** Trust prompts awaiting an in-game answer, keyed by nonce prefix. */
    private final Map<String, CompletableFuture<Boolean>> pendingTrust = new ConcurrentHashMap<>();

    private final PipelineService pipeline;
    private final DeliveryService delivery;
    /** The one reload path (api.pipeline()); every editor reload funnels through it. */
    private final PipelineApi pipelineApi;
    /** Host-shared document services; null → this transport builds its own. */
    private final DocumentStore sharedDocuments;
    private final AuditLog sharedAudit;
    /** Server-computed glyph metadata shared with the authoring API; nullable. */
    private final GlyphMetadataComputer computedMetadata;
    /** The authoring API's change feed; the watcher publishes into it. Nullable. */
    private final AuthoringApiImpl authoringFeed;

    public EditorTransportService(Plugin plugin, DiagnosticsBus diagnostics,
            SchedulingGateway scheduling, PipelineService pipeline,
            DeliveryService delivery, PipelineApi pipelineApi,
            DocumentStore documents, AuditLog audit,
            GlyphMetadataComputer computedMeta, AuthoringApiImpl authoringFeed) {
        this.plugin = plugin;
        this.diagnostics = diagnostics;
        this.scheduling = scheduling;
        this.pipeline = pipeline;
        this.delivery = delivery;
        this.pipelineApi = java.util.Objects.requireNonNull(pipelineApi, "pipelineApi");
        this.sharedDocuments = documents;
        this.sharedAudit = audit;
        this.computedMetadata = computedMeta;
        this.authoringFeed = authoringFeed;
    }

    /** Starts the listener from settings ({@code editor_*} keys). False when disabled/unbound. */
    public boolean start(SettingsState settings, dev.materione.core.storage.StorageService.StorageState storage) {
        if (!settings.getBoolean("editor_enabled", true)) {
            return false;
        }
        String host = settings.getString("editor_host", "127.0.0.1");
        int port = settings.getInt("editor_port", 8089);
        advertiseHost = settings.getString("editor_public_host", "");

        KeyPair identity = new IdentityKeystore(diagnostics, new SecureRandom()).load(storage.storageDir());
        sessions = new EditorSessions(identity, this::prompt, diagnostics);
        RpcDispatcher dispatcher = new RpcDispatcher();
        BaseOps.register(dispatcher, dev.materione.plugin.MaterioneVersion.VERSION,
                () -> java.util.Map.of(
                        "editorRunning", listener != null && listener.isRunning(),
                        "editorPort", boundPort()));
        registerContentOps(dispatcher, storage);
        listener = new EditorListener(
                sessions, new EditorSockets(sessions, dispatcher, diagnostics), dispatcher, diagnostics);
        boolean bound = listener.start(new EditorConfig(host, port));
        if (bound) {
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
        } else {
            listener = null;
            sessions = null;
        }
        if (bound && settings.getBoolean("editor_file_watcher", true)) {
            startFileWatcher(storage);
        }
        return bound;
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
        var documents = sharedDocuments != null ? sharedDocuments
                : new dev.materione.core.document.DocumentStore(
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
                        AuthoringApiImpl feed = authoringFeed;
                        if (feed != null) {
                            feed.publishChange(change.pack(), change.path(), change.deleted());
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
        var documents = sharedDocuments != null ? sharedDocuments
                : new dev.materione.core.document.DocumentStore(
                        storage.contentPacksDir(), storage.storageDir().resolve("revisions"));
        var audit = sharedAudit != null ? sharedAudit
                : new dev.materione.core.document.AuditLog(
                        storage.storageDir().resolve("audit.log"));
        ReloadTrigger trigger = new ReloadTrigger() {
            @Override
            public java.util.concurrent.CompletableFuture<Outcome> reloadFiles(
                    java.util.Set<java.nio.file.Path> files) {
                return runReload(new dev.materione.api.pipeline.ReloadScope.FileSet(files),
                        storage, false);
            }

            @Override
            public java.util.concurrent.CompletableFuture<Outcome> reloadFilesForPreview(
                    java.util.Set<java.nio.file.Path> files, String sessionId) {
                String ownerId = ownerForSession(sessionId);
                return runReload(new dev.materione.api.pipeline.ReloadScope.FileSet(files), storage, false, ownerId);
            }

            @Override
            public java.util.concurrent.CompletableFuture<Outcome> reloadScopeForPreview(
                    dev.materione.api.pipeline.ReloadScope scope, String sessionId) {
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
                    dev.materione.api.pipeline.ReloadScope scope) {
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
     * Server-computed metadata for the studio: a glyph's allocated codepoint,
     * delegated to the shared {@link GlyphMetadataComputer} (the same
     * implementation the authoring API uses — no duplication).
     */
    private com.google.gson.JsonObject computedMeta(
            String pack, String path, com.google.gson.JsonObject parsed) {
        GlyphMetadataComputer computer = computedMetadata;
        if (computer == null) {
            return null;
        }
        Map<String, Object> computed = computer.compute(pack, path, plainMap(parsed));
        return computed == null ? null : (com.google.gson.JsonObject) jsonOf(computed);
    }

    /** JsonObject → plain Java maps/lists/scalars (the metadata computer's input shape). */
    private static Map<String, Object> plainMap(com.google.gson.JsonObject object) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (var entry : object.entrySet()) {
            out.put(entry.getKey(), plainValue(entry.getValue()));
        }
        return out;
    }

    private static Object plainValue(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            return plainMap(element.getAsJsonObject());
        }
        if (element.isJsonArray()) {
            List<Object> items = new ArrayList<>();
            for (var item : element.getAsJsonArray()) {
                items.add(plainValue(item));
            }
            return items;
        }
        com.google.gson.JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsNumber();
        }
        return primitive.getAsString();
    }

    /** Plain Java maps/lists/scalars → JsonObject (the wire shape). */
    private static com.google.gson.JsonElement jsonOf(Object value) {
        if (value instanceof Map<?, ?> map) {
            com.google.gson.JsonObject object = new com.google.gson.JsonObject();
            for (var entry : map.entrySet()) {
                object.add(String.valueOf(entry.getKey()), jsonOf(entry.getValue()));
            }
            return object;
        }
        if (value instanceof List<?> list) {
            var array = new com.google.gson.JsonArray();
            list.forEach(item -> array.add(jsonOf(item)));
            return array;
        }
        if (value instanceof String text) {
            return new com.google.gson.JsonPrimitive(text);
        }
        if (value instanceof Number number) {
            return new com.google.gson.JsonPrimitive(number);
        }
        if (value instanceof Boolean flag) {
            return new com.google.gson.JsonPrimitive(flag);
        }
        if (value == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        return new com.google.gson.JsonPrimitive(value.toString());
    }

    /**
     * Runs any scope through the one reload path — the plugin's
     * {@link PipelineApi} — and broadcasts the outcome to every connected
     * editor: the live preview loop's last mile, shared by save-triggered
     * and manual reloads.
     */
    java.util.concurrent.CompletableFuture<ReloadTrigger.Outcome> runReload(
            dev.materione.api.pipeline.ReloadScope scope,
            dev.materione.core.storage.StorageService.StorageState storage,
            boolean announceAlways) {
        return runReload(scope, storage, announceAlways, "", false);
    }

    java.util.concurrent.CompletableFuture<ReloadTrigger.Outcome> runReload(
            dev.materione.api.pipeline.ReloadScope scope,
            dev.materione.core.storage.StorageService.StorageState storage,
            boolean announceAlways, String previewOwnerId) {
        return runReload(scope, storage, announceAlways, previewOwnerId, true);
    }

    private java.util.concurrent.CompletableFuture<ReloadTrigger.Outcome> runReload(
            dev.materione.api.pipeline.ReloadScope scope,
            dev.materione.core.storage.StorageService.StorageState storage,
            boolean announceAlways, String previewOwnerId, boolean stageCandidate) {
        ActorContext actor = new ActorContext(
                "editor", dev.materione.api.auth.ActorContext.Kind.EDITOR_SESSION,
                previewOwnerId == null || previewOwnerId.isBlank()
                        ? Optional.empty()
                        : Optional.of(UUID.fromString(previewOwnerId)),
                node -> true);
        PipelineApi.DeliveryMode mode = stageCandidate
                ? PipelineApi.DeliveryMode.CANDIDATE_PREVIEW
                : PipelineApi.DeliveryMode.PUBLISH_DEFAULT;
        return pipelineApi
                .reload(actor, new PipelineApi.ReloadRequest(scope, mode))
                .thenApply(result -> result.ok()
                        ? new ReloadTrigger.Outcome(
                                true, result.value().noop(),
                                result.value().itemsLive(), result.value().quarantined())
                        : ReloadTrigger.Outcome.rejected())
                .whenComplete((outcome, error) -> {
                    if (sessions != null && outcome != null && (announceAlways || !outcome.noop())) {
                        dev.materione.web.SessionPush.reload(sessions, scopeLabel(scope), outcome.accepted(),
                                outcome.noop(), outcome.itemsLive(), outcome.quarantined());
                    }
                });
    }

    /** Human-readable scope for push payloads and audit entries. */
    private static String scopeLabel(dev.materione.api.pipeline.ReloadScope scope) {
        if (scope instanceof dev.materione.api.pipeline.ReloadScope.OnePack pack) {
            return "one-pack:" + pack.packId();
        }
        return scope instanceof dev.materione.api.pipeline.ReloadScope.Everything
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

    /**
     * Creates a session and prints its capability URL to the sender; returns
     * the URL (null when the listener is not running) so API callers can hand
     * it to their own surface.
     */
    public String openSession(CommandSender sender) {
        if (listener == null || !listener.isRunning()) {
            sender.sendMessage(Component.text(
                    "the editor listener is not running (disabled in settings or bind failed)",
                    NamedTextColor.RED));
            return null;
        }
        String ownerId = sender instanceof org.bukkit.entity.Player player ? player.getUniqueId().toString() : "";
        String url = listener.openSessionUrl(advertiseHost, ownerId);
        // Stable harness marker: the harness scrapes the URL from the log.
        plugin.getLogger().info("MATERIONE-EDITOR " + url);
        sender.sendMessage(Component.text("Editor session ready — open:", NamedTextColor.GREEN));
        sender.sendMessage(Component.text(url, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(url)));
        sender.sendMessage(Component.text(
                "The part after # is secret and stays in your browser. Approve the connection in game "
                        + "within " + TRUST_PROMPT_TIMEOUT_SECONDS + "s.",
                NamedTextColor.GRAY));
        return url;
    }

    public void stop() {
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
            sessions = null;
        }
        pendingTrust.values().forEach(future -> future.complete(false));
        pendingTrust.clear();
    }

    public boolean isRunning() {
        return listener != null && listener.isRunning();
    }

    public int activeEditors() {
        return listener == null ? 0 : listener.activeSessions();
    }

    public int boundPort() {
        return listener == null ? -1 : listener.boundPort();
    }

    /** In-game approval of one pending hello (command: {@code editor approve <prefix>}). */
    public void approve(CommandSender sender, String noncePrefix) {
        resolve(sender, noncePrefix, true);
    }

    /** In-game denial of one pending hello. */
    public void deny(CommandSender sender, String noncePrefix) {
        resolve(sender, noncePrefix, false);
    }

    /** Closes every live session and pending prompt (no listener restart needed). */
    public void revokeAll(CommandSender sender) {
        if (sessions != null) {
            sessions.closeAll();
        }
        pendingTrust.values().forEach(future -> future.complete(false));
        pendingTrust.clear();
        sender.sendMessage(Component.text("all editor sessions revoked", NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------

    /**
     * The trust decision point: broadcasts an actionable prompt to every
     * admin online plus the console, and completes when someone answers.
     */
    private CompletableFuture<Boolean> prompt(String noncePrefix, String browser) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        CompletableFuture<Boolean> previous = pendingTrust.put(noncePrefix, future);
        if (previous != null) {
            previous.complete(false); // prefix collision: the older request loses
        }
        future.whenComplete((approved, error) -> pendingTrust.remove(noncePrefix, future));

        Component prompt = Component.text("[Materione] ", NamedTextColor.GOLD)
                .append(Component.text("'" + browser + "' wants to connect as editor", NamedTextColor.YELLOW))
                .append(Component.text(" (nonce " + noncePrefix + ") ", NamedTextColor.AQUA))
                .append(Component.text("[Approve]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/materione editor approve " + noncePrefix)))
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

    private void resolve(CommandSender sender, String noncePrefix, boolean approved) {
        if (!sender.hasPermission("materione.admin")) {
            sender.sendMessage(Component.text("no permission (materione.admin)", NamedTextColor.RED));
            return;
        }
        CompletableFuture<Boolean> future = pendingTrust.get(noncePrefix);
        if (future == null || future.isDone()) {
            sender.sendMessage(Component.text(
                    "no pending editor request with nonce " + noncePrefix, NamedTextColor.RED));
            return;
        }
        future.complete(approved);
        sender.sendMessage(Component.text(
                (approved ? "approved" : "denied") + " editor request " + noncePrefix,
                approved ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    }
}
