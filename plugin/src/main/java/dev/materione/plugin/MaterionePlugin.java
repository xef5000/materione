package dev.materione.plugin;

import dev.materione.api.MaterioneAPI;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.platform.ProbeResult;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.MaterioneApiImpl;
import dev.materione.core.authoring.AuthoringApiImpl;
import dev.materione.core.authoring.ReloadHook;
import dev.materione.core.assets.AssetApiImpl;
import dev.materione.core.bootstrap.BootstrapPhase;
import dev.materione.core.bootstrap.BootstrapPhaseRunner;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.document.AssetStore;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.settings.SettingsService;
import dev.materione.core.settings.SettingsService.SettingsState;
import dev.materione.core.storage.StorageService;
import dev.materione.core.storage.StorageService.StorageState;
import dev.materione.core.scheduling.SchedulingGatewayImpl;
import dev.materione.platform.items.ItemMaterialPalette;
import dev.materione.platform.probe.PlatformProbe;
import dev.materione.plugin.command.MaterioneCommand;
import dev.materione.plugin.gui.GuiListener;
import dev.materione.plugin.runtime.DeliveryService;
import dev.materione.plugin.runtime.ItemAutoUpdater;
import dev.materione.plugin.runtime.PipelineService;
import dev.materione.plugin.runtime.api.AdminApiImpl;
import dev.materione.plugin.runtime.api.DeliveryApiImpl;
import dev.materione.plugin.runtime.api.GlyphMetadataComputer;
import dev.materione.plugin.runtime.api.PipelineApiImpl;
import dev.materione.plugin.runtime.api.RuntimeApiImpl;
import dev.materione.plugin.runtime.behavior.BehaviorService;
import dev.materione.plugin.runtime.behavior.ItemBehaviorListener;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The assembly point (master §10): runs the bootstrap phase sequence
 * (plugin.md §1.1) with fail-soft semantics — no phase may crash
 * {@code onEnable} — and emits the stable log markers the integration
 * harness asserts (see harness/README.md).
 *
 * <p>M0 phase order (documented amendment to plugin.md §1.1): diagnostics
 * services come first so the probe's floor failure can be diagnosed; the
 * built-in-registration phase has no content to register at M0, and the
 * pipeline/delivery/editor phases arrive with their milestones.
 */
public final class MaterionePlugin extends JavaPlugin {

    /** bStats plugin id (bstats.org registration); see {@link #startMetrics()}. */
    private static final int BSTATS_PLUGIN_ID = 34981;

    private DiagnosticsBusService diagnostics;
    private RegistryService registries;
    private QuarantineTracker quarantine;
    private SettingsState settings;
    private StorageState storage;
    private ProbeResult probeResult;
    private SchedulingGateway scheduling;
    private PipelineService pipeline;
    private DeliveryService delivery;
    private BehaviorService behavior;
    private dev.materione.core.storage.PlacementIndex placements;
    private dev.materione.plugin.runtime.blocks.CustomBlockService customBlocks;
    private dev.materione.plugin.runtime.furniture.FurnitureService furniture;
    private dev.materione.core.storage.RestartStaging restartStaging;
    private dev.materione.plugin.runtime.DatapackService datapackService;
    private dev.materione.plugin.runtime.mechanics.MechanicsService mechanics;
    private dev.materione.plugin.runtime.EditorTransportService editorTransport;
    /** API-first wiring (api.md §5 Phase 2); null before content load. */
    private MaterioneAPI api;
    private PipelineApiImpl pipelineApi;
    private DocumentStore documents;
    private AuditLog audit;
    private AuthoringApiImpl authoringApi;
    /** bStats handle; null when metrics did not start (see {@link #startMetrics()}). */
    private Metrics metrics;

    /** The API facade; null-tolerant before the content-load phase wires it. */
    public MaterioneAPI api() {
        return api;
    }

    public DiagnosticsBusService diagnostics() {
        return diagnostics;
    }

    public RegistryService registries() {
        return registries;
    }

    public QuarantineTracker quarantine() {
        return quarantine;
    }

    public SettingsState settings() {
        return settings;
    }

    public StorageState storage() {
        return storage;
    }

    public ProbeResult probeResult() {
        return probeResult;
    }

    public PipelineService pipeline() {
        return pipeline;
    }

    /** The thread gateway (commands hop to the player/region that owns their target). */
    public SchedulingGateway scheduling() {
        return scheduling;
    }

    public DeliveryService delivery() {
        return delivery;
    }

    /** The placement index (disposable, generated/); null before content-load. */
    public dev.materione.core.storage.PlacementIndex placements() {
        return placements;
    }

    /** The custom-block world runtime; null before the commands phase. */
    public dev.materione.plugin.runtime.blocks.CustomBlockService customBlocks() {
        return customBlocks;
    }

    /** The furniture cluster runtime; null before the commands phase. */
    public dev.materione.plugin.runtime.furniture.FurnitureService furniture() {
        return furniture;
    }

    /** The native-mechanics runtime; null before built-in registration. */
    public dev.materione.plugin.runtime.mechanics.MechanicsService mechanics() {
        return mechanics;
    }

    /** The staged-restart queue; null before content load. */
    public dev.materione.core.storage.RestartStaging restartStaging() {
        return restartStaging;
    }

    /** The direct-mode editor transport (phase 10); null when disabled/failed. */
    public dev.materione.plugin.runtime.EditorTransportService editorTransport() {
        return editorTransport;
    }

    /** The descriptor, once the probe passed; null before/when unsupported. */
    public PlatformDescriptor descriptorOrNull() {
        return probeResult instanceof ProbeResult.Supported supported ? supported.descriptor() : null;
    }

    @Override
    public void onEnable() {
        BootstrapPhaseRunner runner = new BootstrapPhaseRunner(line -> getLogger().info(line));

        var result = runner.run(List.of(
                BootstrapPhase.degrade("core-services", this::startCoreServices),
                BootstrapPhase.disable("platform-probe", this::probePlatform),
                BootstrapPhase.degrade("storage-init", this::initStorage),
                BootstrapPhase.degrade("built-in-registration", this::registerBuiltins),
                BootstrapPhase.degrade("content-load", this::loadContent),
                BootstrapPhase.degrade("editor-transport", this::startEditorTransport),
                BootstrapPhase.degrade("commands", this::registerCommands)));

        if (result.disabled()) {
            getLogger().severe("Materione did not enable: " + disableReason());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registries.freezeAll();
        for (Diagnostic diagnostic : diagnostics.recent(64)) {
            if (diagnostic.severity() == dev.materione.api.diagnostics.Severity.WARN
                    || diagnostic.severity() == dev.materione.api.diagnostics.Severity.ERROR) {
                getLogger().warning(diagnostic.code() + ": " + diagnostic.message());
            }
        }
        startMetrics();
        // Stable harness marker (harness/README.md) — logged even when degraded.
        getLogger().info("MATERIONE-BOOT-OK " + MaterioneVersion.VERSION);
    }

    @Override
    public void onDisable() {
        try {
            // reverse-order shutdown (plugin.md §1.1): transports first
            if (editorTransport != null) {
                editorTransport.stop();
            }
            if (delivery != null) {
                delivery.stop();
            }
            if (mechanics != null) {
                mechanics.close();
            }
            if (placements != null) {
                placements.close();
            }
            if (diagnostics != null) {
                getLogger().info("diagnostics: " + diagnostics.count(dev.materione.api.diagnostics.Severity.ERROR)
                        + " error(s), " + diagnostics.count(dev.materione.api.diagnostics.Severity.WARN)
                        + " warning(s) this boot");
            }
            if (quarantine != null && quarantine.size() > 0) {
                getLogger().info("quarantined resources: " + quarantine.size());
            }
            if (metrics != null) {
                metrics.shutdown();
            }
            // Stable harness marker: reverse-order shutdown reached the end.
            getLogger().info("MATERIONE-BYE");
        } catch (Throwable t) {
            getLogger().warning("problem during disable: " + t);
        }
    }

    private void startCoreServices() {
        diagnostics = new DiagnosticsBusService(256);
        registries = new RegistryService();
        quarantine = new QuarantineTracker();
        diagnostics.subscribe(d -> {
            // every severity reaches the server log: rules that skip
            // themselves (MTR-0021/0022) and world-data mismatches
            // (MTR-0024) are WARNs an operator must see, not swallow
            var message = d.code() + ": " + d.message();
            switch (d.severity()) {
                case ERROR -> getLogger().severe(message);
                case WARN -> getLogger().warning(message);
                default -> getLogger().info(message);
            }
        });
    }

    private void probePlatform() {
        probeResult = PlatformProbe.probeServer();
        if (probeResult instanceof ProbeResult.Unsupported unsupported) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.PLATFORM_UNSUPPORTED,
                    dev.materione.api.diagnostics.Severity.ERROR,
                    unsupported.reason(), null, null, unsupported.instructions()));
            throw new IllegalStateException(unsupported.reason() + " — " + unsupported.instructions());
        }
    }

    private void initStorage() {
        settings = new SettingsService().load(getDataFolder().toPath().resolve("settings"));
        List<Diagnostic> settingsDiagnostics = new ArrayList<>(settings.diagnostics());
        storage = new StorageService().init(getDataFolder().toPath());
        settingsDiagnostics.addAll(storage.diagnostics());
        settingsDiagnostics.forEach(diagnostics::publish);
        scheduling = new SchedulingGatewayImpl();
    }

    private void registerBuiltins() {
        pipeline = new PipelineService(
                this, diagnostics, quarantine, registries, scheduling, descriptorOrNull());
        pipeline.registerBuiltins(ItemMaterialPalette.supportedItemMaterials());
        // native mechanics through the identical registry path (D5), before
        // content load — item definitions reference them by name
        mechanics = new dev.materione.plugin.runtime.mechanics.MechanicsService(
                this, diagnostics, scheduling, pipeline.runtime(), registries);
        mechanics.registerNatives(registries);
    }

    private void loadContent() {
        // staged restart-required changes apply BEFORE content load (§4.5):
        // M2 ships the machinery with no producers, so this drains a queue
        // that only tests and future classifiers fill
        restartStaging = dev.materione.core.storage.RestartStaging.open(
                storage.storageDir().resolve("staged-restart"));
        var pendingRestart = restartStaging.load();
        for (var entry : pendingRestart) {
            diagnostics.publish(Diagnostic.info(
                    DiagnosticCodes.RESTART_APPLIED,
                    "staged change applied at boot: " + entry.resource() + " (" + entry.reason() + ")"));
        }
        if (!pendingRestart.isEmpty()) {
            restartStaging.clear();
        }
        // the world-datapack applier (M4): snapshots what this boot loaded so
        // painting/jukebox deploys are honestly reported restart-required.
        // Registry mode (1.21.6+ lifecycle): the bootstrap already registered
        // paintings/songs — the applier only tracks the registered hash and
        // keeps the world folder clean. RegistryCapabilities is pure
        // reflection, safe to load on the 1.21.4 floor.
        datapackService = new dev.materione.plugin.runtime.DatapackService(
                this, diagnostics, storage.storageDir(), restartStaging,
                dev.materione.modern.RegistryCapabilities.hasFullRegistrySeam());
        delivery = new DeliveryService(this, diagnostics, scheduling, storage.storageDir());
        delivery.start(settings);
        getServer().getPluginManager().registerEvents(delivery, this);
        wireApi();
        var summary = reloadContent();
        if (summary.skipped()) {
            diagnostics.publish(Diagnostic.warn(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "initial content load skipped (pipeline unavailable or aborted)"));
            return;
        }
        getLogger().info("content loaded: " + summary.itemsLive() + " item(s) live, "
                + summary.quarantined() + " quarantined"
                + (summary.packSha256() == null ? ", pack unchanged" : ""));
        // Stable harness marker: the M1 content pipeline ran to apply.
        getLogger().info("MATERIONE-CONTENT-OK items=" + summary.itemsLive()
                + " defs=" + summary.itemDefinitions()
                + " pack=" + (summary.packSha256() == null ? "noop" : summary.packSha256().substring(0, 12)));
        if (!delivery.currentUrl().isEmpty()) {
            // Stable harness marker: the pack HTTP backend serves the new pack.
            getLogger().info("MATERIONE-DELIVERY-OK " + delivery.currentUrl());
        }
        // the datapack acceptance gate: painting/jukebox content exists only
        // if the vanilla pack repository actually took the deployed artifact.
        // A pack in the folder but absent from the manager was ignored by the
        // server (wrong location, format rejection) — never stay silent.
        // Registry mode has no artifact to accept; the bootstrap's
        // MATERIONE-REGISTRY-OK marker is the boot evidence instead.
        if (datapackService != null && !datapackService.pendingRestartRequired()) {
            boolean accepted = datapackService.isAcceptedByServer();
            if (!datapackService.isRegistryMode()) {
                getLogger().info("MATERIONE-DATAPACK-ACCEPTED " + accepted);
            }
            if (!accepted) {
                diagnostics.publish(Diagnostic.warn(
                        dev.materione.api.diagnostics.DiagnosticCodes.DATAPACK_NOT_ACCEPTED,
                        "the deployed content datapack was not accepted by the server's pack"
                                + " repository — paintings/jukebox content will not exist;"
                                + " restart once more, then check /materione doctor"));
            }
        }
    }

    /** One composed reload: pipeline → compile → apply → deliver (§4). */
    public dev.materione.api.pipeline.ReloadSummary reloadContent() {
        var summary = pipeline.reload(
                storage.contentPacksDir(), storage.generatedDir(), storage.storageDir());
        if (!summary.skipped() && summary.packSha256() != null && summary.packZip() != null) {
            // deliver after apply — delivery failure never rolls apply back (§4.6).
            // Changed pack bytes always publish (a boot has no editor flow to
            // approve a staged candidate — serving the old pack here would
            // outlive its own content).
            delivery.publish(summary.packZip(), summary.packSha256());
        }
        if (datapackService != null) {
            datapackService.apply(summary.stagedDatapackZip(), summary.datapackSha256());
        }
        return summary;
    }

    /**
     * API-first wiring (api.md §5 Phase 2): assembles the four sub-APIs over
     * the services the content-load phase created, so every authoring,
     * asset, pipeline and delivery action has a typed surface. The editor
     * transport shares these instances — one document store, one audit log,
     * one reload path.
     */
    private void wireApi() {
        java.nio.file.Path packsRoot = storage.contentPacksDir();
        documents = new DocumentStore(packsRoot, storage.storageDir().resolve("revisions"));
        audit = new AuditLog(storage.storageDir().resolve("audit.log"));
        ModelTransactionService modelTransactions =
                new ModelTransactionService(documents, packsRoot, audit);
        AssetStore assetStore = new AssetStore(packsRoot, audit);
        GlyphMetadataComputer glyphMeta = new GlyphMetadataComputer(pipeline);
        pipelineApi = new PipelineApiImpl(
                this, scheduling, pipeline, quarantine, restartStaging, delivery,
                packsRoot, storage.generatedDir(), storage.storageDir(),
                summary -> {
                    if (datapackService != null) {
                        datapackService.apply(
                                summary.stagedDatapackZip(), summary.datapackSha256());
                    }
                });
        // The live-preview seam: document saves reload exactly the touched
        // file; pack-wide operations (assets, model transactions, folder
        // moves) reload the whole pack — preview delivery to the actor.
        ReloadHook previewReload = (actor, pack, changed) -> {
            dev.materione.api.pipeline.ReloadScope scope = Files.isDirectory(changed)
                    ? new dev.materione.api.pipeline.ReloadScope.OnePack(pack)
                    : new dev.materione.api.pipeline.ReloadScope.FileSet(Set.of(changed));
            pipelineApi.reload(actor, new dev.materione.api.pipeline.PipelineApi.ReloadRequest(
                    scope, dev.materione.api.pipeline.PipelineApi.DeliveryMode.CANDIDATE_PREVIEW));
        };
        authoringApi = new AuthoringApiImpl(
                documents, audit, packsRoot, modelTransactions, glyphMeta,
                () -> pipeline.runner().lastGraph(), previewReload, assetStore,
                pipeline::contentTypes);
        AssetApiImpl assetApi = new AssetApiImpl(assetStore, packsRoot, previewReload);
        DeliveryApiImpl deliveryApi = new DeliveryApiImpl(delivery);
        RuntimeApiImpl runtimeApi = new RuntimeApiImpl(
                this, scheduling, pipeline,
                () -> customBlocks, () -> furniture);
        AdminApiImpl adminApi = new AdminApiImpl(
                () -> probeResult, registries, diagnostics, quarantine,
                () -> pipeline, () -> restartStaging, () -> delivery,
                getDataFolder().toPath().resolve("settings"), audit, () -> editorTransport,
                new SettingsService(),
                () -> settings, () -> storage, () -> datapackService);
        api = new MaterioneApiImpl(
                MaterioneVersion.VERSION, registries, diagnostics,
                this::descriptorOrNull, scheduling,
                authoringApi, assetApi, pipelineApi, deliveryApi, runtimeApi, adminApi, mechanics);
        // the public surface any addon can look up (api.md §4.1)
        getServer().getServicesManager().register(
                dev.materione.api.MaterioneAPI.class, api, this,
                org.bukkit.plugin.ServicePriority.Normal);
    }

    /** The world-datapack applier; null until content load begins. */
    public dev.materione.plugin.runtime.DatapackService datapackService() {
        return datapackService;
    }

    /** Phase 10 (plugin.md §1.1): the direct-mode editor listener, if enabled. */
    private void startEditorTransport() {
        if (pipelineApi == null) {
            // content never loaded — there is no API to serve the editor through
            return;
        }
        editorTransport = new dev.materione.plugin.runtime.EditorTransportService(
                this, diagnostics, scheduling, pipeline, api, documents, audit,
                authoringApi);
        editorTransport.behaviorVocabulary(() -> behavior == null
                ? dev.materione.api.registry.BehaviorVocabulary.empty() : behavior.vocabulary());
        editorTransport.start(settings, storage);
    }

    private void registerCommands() {
        // paper plugins ignore the old plugin.yml commands block: the command
        // registers through the server's command map, delegating to the same
        // executor/tab-completer pair (MaterioneCommandAdapter)
        getServer().getCommandMap().register("materione",
                new dev.materione.plugin.command.MaterioneCommandAdapter(new MaterioneCommand(this)));
        // permissions were declared in plugin.yml (default: op) — declare them
        // programmatically so the §9.2 taxonomy stays exactly as documented
        for (String permission : List.of(
                "materione.editor", "materione.pipeline", "materione.dev",
                "materione.give", "materione.admin")) {
            if (getServer().getPluginManager().getPermission(permission) == null) {
                getServer().getPluginManager().addPermission(new org.bukkit.permissions.Permission(
                        permission, org.bukkit.permissions.PermissionDefault.OP));
            }
        }
        getServer().getPluginManager().registerEvents(
                new ItemAutoUpdater(this, pipeline.runtime(), diagnostics, scheduling), this);
        // gui click router (holder identity — no title matching): one
        // listener for every Materione menu, admin browser today, M6 menus later
        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        // ECA bridge (runtime-systems §2): rules resolve from the live shadow
        // at event entry, so the listener registers once and never churns
        behavior = new BehaviorService(this, diagnostics, scheduling, pipeline.runtime());
        getServer().getPluginManager().registerEvents(new ItemBehaviorListener(behavior, diagnostics), this);
        // custom-block world runtime (runtime-systems §4): placement index is
        // disposable generated state — rebuilt from chunk scans if lost
        placements = dev.materione.core.storage.PlacementIndex.load(
                storage.generatedDir().resolve("index").resolve("placements.jsonl"));
        customBlocks = new dev.materione.plugin.runtime.blocks.CustomBlockService(
                this, diagnostics, scheduling, pipeline.runtime(), behavior, placements);
        getServer().getPluginManager().registerEvents(customBlocks, this);
        // spawn-area chunks loaded before this listener existed: rebuild any
        // index entries they carry now, straight from chunk-PDC truth (§4.2)
        customBlocks.reconcileLoadedChunks();
        // furniture cluster runtime (runtime-systems §5): entity-carried,
        // so no placement index — roots persist in their chunks
        furniture = new dev.materione.plugin.runtime.furniture.FurnitureService(
                this, diagnostics, scheduling, pipeline.runtime(), behavior);
        getServer().getPluginManager().registerEvents(furniture, this);
        // Preserve HIGH-priority ordering: the ECA bridge precedes native mechanics.
        mechanics.start();
        // text pipeline (M4 runtime-systems §6): glyph tags in item text,
        // chat, signs, books and anvil renames; the supplier reads the live
        // shadow, so registry swaps apply without listener churn
        dev.materione.platform.text.GlyphText.register(
                () -> pipeline.runtime().registries().view(dev.materione.core.runtime.RuntimeGlyphs.KEY));
        dev.materione.platform.text.GlyphText.registerEffects(
                () -> pipeline.runtime().registries().view(dev.materione.core.runtime.RuntimeEffects.KEY));
        getServer().getPluginManager().registerEvents(
                new dev.materione.plugin.runtime.text.GlyphTextListeners(
                        () -> pipeline.runtime().registries().view(dev.materione.core.runtime.RuntimeGlyphs.KEY),
                        delivery::canRenderGlyphs),
                this);
        dev.materione.plugin.runtime.blocks.PhysicsProbe.enforceOrReport(diagnostics);
    }

    private String disableReason() {
        return probeResult instanceof ProbeResult.Unsupported unsupported
                ? unsupported.reason()
                : "bootstrap failure (see log above)";
    }

    /**
     * Anonymous usage metrics (bStats). Fail-soft: any failure is a warning,
     * never a failed enable. Server owners opt out with {@code enabled: false}
     * in {@code plugins/bStats/config.yml}; the harness and probe servers
     * always do.
     */
    private void startMetrics() {
        try {
            metrics = new Metrics(this, BSTATS_PLUGIN_ID);
            String editorTransport = editorTransportLabel();
            metrics.addCustomChart(new SimplePie("editor_transport", () -> editorTransport));
        } catch (Throwable t) {
            getLogger().warning("bStats metrics unavailable: " + t);
        }
    }

    /**
     * The editor transport this boot was configured with (the bStats chart
     * value). Mirrors {@code EditorTransportService}: anything but "local" is
     * the cloud relay.
     */
    private String editorTransportLabel() {
        if (settings == null) {
            return "unknown";
        }
        if (!settings.getBoolean("editor_enabled", true)) {
            return "disabled";
        }
        String mode = settings.getString("editor_mode", "cloud");
        return "local".equalsIgnoreCase(mode) ? "local" : "cloud";
    }
}
