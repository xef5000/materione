package dev.materione.plugin;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.platform.ProbeResult;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.bootstrap.BootstrapPhase;
import dev.materione.core.bootstrap.BootstrapPhaseRunner;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.settings.SettingsService;
import dev.materione.core.settings.SettingsService.SettingsState;
import dev.materione.core.storage.StorageService;
import dev.materione.core.storage.StorageService.StorageState;
import dev.materione.core.scheduling.SchedulingGatewayImpl;
import dev.materione.platform.probe.PlatformProbe;
import dev.materione.plugin.command.MaterioneCommand;
import dev.materione.plugin.runtime.ItemAutoUpdater;
import dev.materione.plugin.runtime.PipelineService;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.PluginCommand;
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

    private DiagnosticsBusService diagnostics;
    private RegistryService registries;
    private QuarantineTracker quarantine;
    private SettingsState settings;
    private StorageState storage;
    private ProbeResult probeResult;
    private SchedulingGateway scheduling;
    private PipelineService pipeline;

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
        // Stable harness marker (harness/README.md) — logged even when degraded.
        getLogger().info("MATERIONE-BOOT-OK " + MaterioneVersion.VERSION);
    }

    @Override
    public void onDisable() {
        try {
            if (diagnostics != null) {
                getLogger().info("diagnostics: " + diagnostics.count(dev.materione.api.diagnostics.Severity.ERROR)
                        + " error(s), " + diagnostics.count(dev.materione.api.diagnostics.Severity.WARN)
                        + " warning(s) this boot");
            }
            if (quarantine != null && quarantine.size() > 0) {
                getLogger().info("quarantined resources: " + quarantine.size());
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
            if (d.severity() == dev.materione.api.diagnostics.Severity.ERROR) {
                getLogger().severe(d.code() + ": " + d.message());
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
        pipeline.registerBuiltins();
    }

    private void loadContent() {
        var summary = pipeline.reload(
                storage.contentPacksDir(), storage.generatedDir(), storage.storageDir());
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
    }

    private void registerCommands() {
        PluginCommand command = getCommand("materione");
        if (command == null) {
            diagnostics.publish(Diagnostic.error(
                    DiagnosticCodes.COMMAND_REGISTRATION_FAILED,
                    "command 'materione' missing from plugin.yml"));
            return;
        }
        MaterioneCommand executor = new MaterioneCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvents(
                new ItemAutoUpdater(this, pipeline.runtime(), diagnostics, scheduling), this);
    }

    private String disableReason() {
        return probeResult instanceof ProbeResult.Unsupported unsupported
                ? unsupported.reason()
                : "bootstrap failure (see log above)";
    }
}
