package dev.materione.plugin.runtime;

import dev.materione.api.MaterioneAPI;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.assets.AssetApi;
import dev.materione.api.content.ContentType;
import dev.materione.api.delivery.DeliveryApi;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
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
    private dev.materione.web.relay.RelaySessions relaySessions;
    private dev.materione.web.EditorWorkstations workstations;
    private String relayBaseUrl = "";
    private String editorMode = "cloud";
    private AutoCloseable diagnosticsSubscription;
    private dev.materione.core.document.PackWatcher packWatcher;
    private String advertiseHost = "";
    /** {@code editor_direct_hint}: whether/when cloud sessions advertise the local listener (web.md §4.4). */
    private dev.materione.web.relay.DirectHint.Mode directHintMode = dev.materione.web.relay.DirectHint.Mode.OFF;
    /** {@code editor_public_url}: an https:// origin fronting the listener (TLS reverse proxy), or blank. */
    private String publicUrl = "";
    /** Trust prompts awaiting an in-game answer, keyed by nonce prefix. */
    private final Map<String, CompletableFuture<dev.materione.web.TrustPrompt.Decision>> pendingTrust =
            new ConcurrentHashMap<>();

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
        directHintMode = dev.materione.web.relay.DirectHint.Mode.parse(
                settings.getString("editor_direct_hint", "off"));
        publicUrl = settings.getString("editor_public_url", "");

        KeyPair identity = new IdentityKeystore(diagnostics, new SecureRandom()).load(storage.storageDir());
        workstations = new dev.materione.web.EditorWorkstations(
                storage.storageDir().resolve("editor-workstations.json"));
        sessions = new EditorSessions(identity, this::prompt, workstations, diagnostics);
        RpcDispatcher dispatcher = opsDispatcher(storage);
        relaySessions = new dev.materione.web.relay.RelaySessions(sessions, dispatcher, diagnostics);
        listener = new EditorListener(
                sessions, new EditorSockets(sessions, dispatcher, diagnostics), dispatcher, diagnostics);
        // editor_local_listener: false is the shared-host switch — no port is
        // ever opened and the cloud relay is the only surface (web.md §4.4).
        boolean wantListener = settings.getBoolean("editor_local_listener", true);
        boolean bound = wantListener && listener.start(new EditorConfig(host, port));
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
        // One reload push channel: save-triggered and manual runs alike
        // announce here (manual always, saves only on real changes).
        reloadSubscription = api.pipeline().subscribeReloads(event -> {
            EditorSessions current = sessions;
            if (current != null && (event.announceAlways() || !event.noop() || !event.accepted())) {
                dev.materione.web.SessionPush.reload(current, event.scope(),
                        event.accepted(), event.noop(), event.itemsLive(), event.quarantined());
            }
        });
        // The watcher feeds every connected editor (relay sessions included),
        // so it does not depend on the local listener being bound.
        if (settings.getBoolean("editor_file_watcher", true)) {
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
                    },
                    () -> {
                        AuthoringApiImpl feed = authoringFeed;
                        if (feed != null) {
                            feed.invalidateResourceSummaries();
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
     * The complete editor op set over this host's api, without any transport:
     * {@link #start} serves it to sessions, and the cloud editor's demo
     * snapshot (web.md §6) is recorded from the very same dispatcher.
     */
    public RpcDispatcher opsDispatcher(dev.materione.core.storage.StorageService.StorageState storage) {
        RpcDispatcher dispatcher = new RpcDispatcher();
        BaseOps.register(dispatcher, dev.materione.plugin.MaterioneVersion.VERSION,
                () -> java.util.Map.of(
                        "editorRunning", editorRunning(),
                        "editorPort", boundPort(),
                        "editorMode", editorMode,
                        "relayBaseUrl", relayBaseUrl,
                        // picks the editor's vanilla icon manifest (web.md §4.2)
                        "minecraftVersion", minecraftVersion()));
        registerContentOps(dispatcher, storage);
        return dispatcher;
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
                () -> diagnostics.recent(100), actorFor, this::behaviorVocabulary, this::guiComponents);
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

    private java.util.function.Supplier<dev.materione.api.registry.BehaviorVocabulary> behaviorVocabulary =
            dev.materione.api.registry.BehaviorVocabulary::empty;

    /** Wires the behavior engine's vocabulary; mechanics are read from their registry per call. */
    public void behaviorVocabulary(
            java.util.function.Supplier<dev.materione.api.registry.BehaviorVocabulary> vocabulary) {
        this.behaviorVocabulary = java.util.Objects.requireNonNull(vocabulary, "vocabulary");
    }

    /** The behavior vocabulary plus every registered mechanic, built-in or addon (D5). */
    private dev.materione.api.registry.BehaviorVocabulary behaviorVocabulary() {
        var mechanics = new ArrayList<dev.materione.api.registry.BehaviorVocabulary.Mechanic>();
        api.registries().byKey(RegistryKeys.MECHANICS).ifPresent(view -> {
            for (var entry : ((Registry<?>) view).entries()) {
                boolean repeatable = entry.entry() instanceof dev.materione.api.mechanics.Mechanic mechanic
                        && mechanic.repeatable();
                mechanics.add(new dev.materione.api.registry.BehaviorVocabulary.Mechanic(
                        entry.id().toString(), entry.description(), repeatable, entry.registeredBy()));
            }
        });
        mechanics.sort(java.util.Comparator.comparing(
                dev.materione.api.registry.BehaviorVocabulary.Mechanic::id));
        return behaviorVocabulary.get().withMechanics(mechanics);
    }

    /** The {@code gui-components} registry entries, built-in and addon, read per describe call. */
    private java.util.List<dev.materione.api.registry.RegistryEntry<?>> guiComponents() {
        java.util.List<dev.materione.api.registry.RegistryEntry<?>> out = new ArrayList<>();
        api.registries().byKey(RegistryKeys.GUI_COMPONENTS).ifPresent(view -> {
            for (var entry : ((Registry<?>) view).entries()) {
                out.add(entry);
            }
        });
        return out;
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
     * Opens a session per {@code editor_mode} — cloud relay (default) or
     * local — prints the capability URL to the sender, and returns it (null
     * when no surface is available) so API callers can hand it to their own
     * surface.
     */
    public String openSession(CommandSender sender) {
        return "local".equalsIgnoreCase(editorMode)
                ? openLocalSession(sender)
                : openCloudSession(sender);
    }

    /** The always-available direct-mode session (power users / relay outage). */
    public String openLocalSession(CommandSender sender) {
        if (listener == null || !listener.isRunning()) {
            sender.sendMessage(Component.text(
                    "the local editor listener is not running — enable it with editor_enabled: true and editor_local_listener: true "
                            + "in settings.yml (or check the boot log for MTR-0030)",
                    NamedTextColor.RED));
            return null;
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
        sender.sendMessage(Component.text(
                "This link only works from a computer that can reach port " + listener.boundPort()
                        + " on this server directly. Behind a hosting panel or NAT it will not — use "
                        + "/materione editor (the cloud editor) instead.",
                NamedTextColor.GRAY));
        return url;
    }

    /**
     * The default path (web.md §4.1 over the mailbox): create the channel,
     * upload the sealed blob, print the cloud editor URL. When the relay is
     * unreachable this does NOT fall back to the local listener (MTR-0050):
     * whether that port is reachable from the admin's browser is exactly
     * what a server cannot know, and a link to an unreachable port is worse
     * than a clear refusal (web.md §4.4).
     */
    public String openCloudSession(CommandSender sender) {
        if (relaySessions == null) {
            sender.sendMessage(Component.text(
                    "the editor transport never started this boot", NamedTextColor.RED));
            return null;
        }
        String ownerId = sender instanceof org.bukkit.entity.Player player ? player.getUniqueId().toString() : "";
        String directHint = directHintFor(sender);
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
                    : " The page will check whether your browser can reach this server's local editor "
                            + "directly and quietly use it if so; otherwise it stays on the cloud.";
            sender.sendMessage(Component.text(
                    "The part after # is secret and stays in your browser. Approve the connection in game "
                            + "within " + TRUST_PROMPT_TIMEOUT_SECONDS + "s." + extra,
                    NamedTextColor.GRAY));
            return ticket.url();
        } catch (dev.materione.web.relay.RelayException e) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.EDITOR_RELAY_UNREACHABLE,
                    dev.materione.api.diagnostics.Severity.WARN,
                    "relay " + relayBaseUrl + " unavailable: " + e.getMessage(),
                    null, null,
                    "check relay_base_url in settings.yml and this server's outbound HTTPS; "
                            + "/materione editor local needs the editor port reachable from your browser"));
            sender.sendMessage(Component.text(
                    "cloud editor unreachable (" + e.getMessage() + ") — no editor session was opened.",
                    NamedTextColor.RED));
            sender.sendMessage(Component.text(
                    "Retry in a moment. If this server's editor port is reachable from your own "
                            + "computer you can use /materione editor local instead — on most hosting "
                            + "panels and behind NAT it is not reachable, so that link would not load.",
                    NamedTextColor.YELLOW));
            return null;
        }
    }

    /**
     * The direct-mode hint for this sender's cloud session, or null. Policy
     * lives in {@link dev.materione.web.relay.DirectHint}; the hint is only
     * an invitation to probe — the browser proves reachability itself.
     */
    private String directHintFor(CommandSender sender) {
        java.net.InetAddress address = null;
        if (sender instanceof org.bukkit.entity.Player player) {
            java.net.InetSocketAddress socket = player.getAddress();
            address = socket == null ? null : socket.getAddress();
        }
        return dev.materione.web.relay.DirectHint.resolve(
                directHintMode,
                listener != null && listener.isRunning(),
                listener == null ? "" : listener.boundHost(),
                listener == null ? -1 : listener.boundPort(),
                publicUrl, advertiseHost, relayBaseUrl, address);
    }

    public void stop() {
        if (relaySessions != null) {
            relaySessions.stopAll();
        }
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
        }
        sessions = null;
        pendingTrust.values().forEach(future -> future.complete(TrustPrompt.Decision.DENIED));
        pendingTrust.clear();
    }

    public boolean isRunning() {
        return editorRunning();
    }

    /** True when the loopback listener is up (the relay needs no listener). */
    public boolean localListenerRunning() {
        return listener != null && listener.isRunning();
    }

    /** The configured default surface ("cloud" relay or "local"). */
    public String editorMode() {
        return editorMode;
    }

    public int activeEditors() {
        // One shared session registry serves both transports — counting it
        // once per surface would double every relay session (the listener's
        // count already includes them).
        return sessions == null ? 0 : sessions.active().size();
    }

    public int boundPort() {
        return listener == null ? -1 : listener.boundPort();
    }

    /** The probed Minecraft version, or empty before the probe resolves (never null: Map.of). */
    private String minecraftVersion() {
        try {
            return api.platform().minecraftVersion();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * In-game approval of one pending hello (command:
     * {@code editor approve <prefix> [trust]}).
     */
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
