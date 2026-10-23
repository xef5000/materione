package dev.materione.plugin.runtime;

import dev.materione.api.MaterioneAPI;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.assets.AssetApi;
import dev.materione.api.content.ContentType;
import dev.materione.api.delivery.DeliveryApi;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.pipeline.PipelineApi;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.authoring.AuthoringApiImpl;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.settings.SettingsService.SettingsState;
import dev.materione.web.BaseOps;
import dev.materione.web.EditorConfig;
import dev.materione.web.EditorListener;
import dev.materione.web.EditorSession;
import dev.materione.web.EditorSessions;
import dev.materione.web.EditorSockets;
import dev.materione.web.IdentityKeystore;
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
 * <p>Since the API-first transformation, every op is served from the
 * {@link MaterioneAPI} facade — one reload path ({@code api.pipeline()}) for
 * the editor, the commands and future MCP callers — and the file-watcher
 * feed also reaches the authoring API's change subscription.
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
    /** The facade: the only surface the editor's ops are served through. */
    private final MaterioneAPI api;
    /** Host-shared document services; null → this transport builds its own. */
    private final DocumentStore sharedDocuments;
    private final AuditLog sharedAudit;
    /** The authoring API's change feed; the watcher publishes into it. Nullable. */
    private final AuthoringApiImpl authoringFeed;
    private AutoCloseable reloadSubscription;

    public EditorTransportService(Plugin plugin, DiagnosticsBus diagnostics,
            SchedulingGateway scheduling, PipelineService pipeline,
            MaterioneAPI api, DocumentStore documents, AuditLog audit,
            AuthoringApiImpl authoringFeed) {
        this.plugin = plugin;
        this.diagnostics = diagnostics;
        this.scheduling = scheduling;
        this.pipeline = pipeline;
        this.api = java.util.Objects.requireNonNull(api, "api");
        this.sharedDocuments = documents;
        this.sharedAudit = audit;
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
        if (bound) {
            // One reload push channel: save-triggered and manual runs alike
            // announce here (manual always, saves only on real changes).
            reloadSubscription = api.pipeline().subscribeReloads(event -> {
                EditorSessions current = sessions;
                if (current != null && (event.announceAlways() || !event.noop())) {
                    dev.materione.web.SessionPush.reload(current, event.scope(),
                            event.accepted(), event.noop(), event.itemsLive(), event.quarantined());
                }
            });
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
     * served entirely from the {@link MaterioneAPI} sub-surfaces — this host
     * supplies the session-bound {@link ActorContext}s and nothing else.
     */
    private void registerContentOps(RpcDispatcher dispatcher,
            dev.materione.core.storage.StorageService.StorageState storage) {
        var audit = sharedAudit != null ? sharedAudit
                : new dev.materione.core.document.AuditLog(
                        storage.storageDir().resolve("audit.log"));
        // Editor sessions act with their owner's identity; per-node checks ride
        // the in-game trust flow (master §9.2) until relay mode tightens them.
        java.util.function.Function<String, ActorContext> actorFor =
                attribution -> new ActorContext(
                        attribution, ActorContext.Kind.EDITOR_SESSION,
                        ownerUuidOfCurrentSession(), node -> true);
        dev.materione.web.rpc.ops.EditorContentOps.register(
                dispatcher, api.authoring(), this::contentTypes,
                () -> diagnostics.recent(100), actorFor);
        dev.materione.web.rpc.ops.AuthoringModelOps.register(
                dispatcher, api.authoring(), actorFor);
        dev.materione.web.rpc.ops.AssetOps.register(dispatcher, api.assets(), actorFor);
        dev.materione.web.rpc.ops.AdminOps.register(
                dispatcher, api.admin(), api.pipeline(), actorFor,
                (actor, target) -> audit.record(actor, "pipeline.reload", target));
        dev.materione.web.rpc.ops.DeliveryOps.register(dispatcher, api.delivery(), actorFor);
    }

    /** The session owner bound to the dispatching request, if any. */
    private java.util.Optional<java.util.UUID> ownerUuidOfCurrentSession() {
        EditorSessions current = sessions;
        String sessionId = RpcDispatcher.currentSessionId();
        if (current == null || sessionId == null) {
            return java.util.Optional.empty();
        }
        EditorSession session = current.find(sessionId);
        String owner = session == null ? null : session.ownerId();
        try {
            return owner == null || owner.isBlank()
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(java.util.UUID.fromString(owner));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty(); // console-origin session
        }
    }

    /** Registered content types through the api's registry view (D5). */
    private java.util.List<ContentType> contentTypes() {
        java.util.List<ContentType> out = new ArrayList<>();
        api.registries().byKey(RegistryKeys.CONTENT_TYPES).ifPresent(view -> {
            for (var entry : ((Registry<?>) view).entries()) {
                if (entry.entry() instanceof ContentType type) {
                    out.add(type);
                }
            }
        });
        return out;
    }

    /**
     * Runs any scope through the one reload path — the api's
     * {@code pipeline()} — for host-side callers (the reload test, console
     * flows). Push announcements happen once, in the reload subscription.
     */
    java.util.concurrent.CompletableFuture<Outcome> runReload(
            dev.materione.api.pipeline.ReloadScope scope,
            boolean announceAlways, String previewOwnerId, boolean stageCandidate) {
        ActorContext actor = new ActorContext(
                "editor", ActorContext.Kind.EDITOR_SESSION,
                previewOwnerId == null || previewOwnerId.isBlank()
                        ? java.util.Optional.empty()
                        : java.util.Optional.of(java.util.UUID.fromString(previewOwnerId)),
                node -> true);
        PipelineApi.DeliveryMode mode = stageCandidate
                ? PipelineApi.DeliveryMode.CANDIDATE_PREVIEW
                : PipelineApi.DeliveryMode.PUBLISH_DEFAULT;
        return api.pipeline()
                .reload(actor, new PipelineApi.ReloadRequest(scope, mode, announceAlways))
                .thenApply(result -> result.ok()
                        ? new Outcome(
                                true, result.value().noop(),
                                result.value().itemsLive(), result.value().quarantined())
                        : Outcome.rejected());
    }

    /** The reload outcome host callers wait on. */
    public record Outcome(boolean accepted, boolean noop, int itemsLive, int quarantined) {
        public static Outcome rejected() {
            return new Outcome(false, true, -1, -1);
        }
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
        if (reloadSubscription != null) {
            try {
                reloadSubscription.close();
            } catch (Exception e) {
                // unsubscribing is best-effort (D6)
            }
            reloadSubscription = null;
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
