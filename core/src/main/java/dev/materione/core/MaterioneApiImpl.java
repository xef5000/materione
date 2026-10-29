package dev.materione.core;

import dev.materione.api.MaterioneAPI;
import dev.materione.api.admin.AdminApi;
import dev.materione.api.assets.AssetApi;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.delivery.DeliveryApi;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.gui.GuiComponentsApi;
import dev.materione.api.logic.LogicApi;
import dev.materione.api.mechanics.MechanicsApi;
import dev.materione.api.pipeline.PipelineApi;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.registry.RegistryView;
import dev.materione.api.runtime.RuntimeApi;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.registry.RegistryService;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The {@link MaterioneAPI} implementation, assembled from core services. The
 * platform descriptor arrives as a supplier because the plugin wires it after
 * the probe phase; addons only ever see the facade.
 *
 * <p>Sub-APIs may be absent during early bootstrap or in tests — the
 * accessors fail with a clear {@link IllegalStateException} instead of
 * returning null, so a consumer notices a wiring gap at the call site.
 */
public final class MaterioneApiImpl implements MaterioneAPI {

    private final String version;
    private final RegistryView registries;
    private final DiagnosticsBus diagnostics;
    private final Supplier<PlatformDescriptor> platform;
    private final SchedulingGateway scheduling;
    private final AuthoringApi authoring;
    private final AssetApi assets;
    private final PipelineApi pipeline;
    private final DeliveryApi delivery;
    private final RuntimeApi runtime;
    private final AdminApi admin;
    private final MechanicsApi mechanics;
    private final GuiComponentsApi guiComponents;
    private final LogicApi logic;

    public MaterioneApiImpl(
            String version,
            RegistryService registries,
            DiagnosticsBus diagnostics,
            Supplier<PlatformDescriptor> platform,
            SchedulingGateway scheduling,
            AuthoringApi authoring,
            AssetApi assets,
            PipelineApi pipeline,
            DeliveryApi delivery) {
        this(version, registries, diagnostics, platform, scheduling,
                authoring, assets, pipeline, delivery, null, null);
    }

    public MaterioneApiImpl(
            String version,
            RegistryService registries,
            DiagnosticsBus diagnostics,
            Supplier<PlatformDescriptor> platform,
            SchedulingGateway scheduling,
            AuthoringApi authoring,
            AssetApi assets,
            PipelineApi pipeline,
            DeliveryApi delivery,
            RuntimeApi runtime,
            AdminApi admin) {
        this(version, registries, diagnostics, platform, scheduling,
                authoring, assets, pipeline, delivery, runtime, admin, null);
    }

    public MaterioneApiImpl(
            String version,
            RegistryService registries,
            DiagnosticsBus diagnostics,
            Supplier<PlatformDescriptor> platform,
            SchedulingGateway scheduling,
            AuthoringApi authoring,
            AssetApi assets,
            PipelineApi pipeline,
            DeliveryApi delivery,
            RuntimeApi runtime,
            AdminApi admin,
            MechanicsApi mechanics) {
        this(version, registries, diagnostics, platform, scheduling,
                authoring, assets, pipeline, delivery, runtime, admin, mechanics, null);
    }

    public MaterioneApiImpl(
            String version,
            RegistryService registries,
            DiagnosticsBus diagnostics,
            Supplier<PlatformDescriptor> platform,
            SchedulingGateway scheduling,
            AuthoringApi authoring,
            AssetApi assets,
            PipelineApi pipeline,
            DeliveryApi delivery,
            RuntimeApi runtime,
            AdminApi admin,
            MechanicsApi mechanics,
            GuiComponentsApi guiComponents) {
        this(version, registries, diagnostics, platform, scheduling,
                authoring, assets, pipeline, delivery, runtime, admin, mechanics, guiComponents, null);
    }

    public MaterioneApiImpl(
            String version,
            RegistryService registries,
            DiagnosticsBus diagnostics,
            Supplier<PlatformDescriptor> platform,
            SchedulingGateway scheduling,
            AuthoringApi authoring,
            AssetApi assets,
            PipelineApi pipeline,
            DeliveryApi delivery,
            RuntimeApi runtime,
            AdminApi admin,
            MechanicsApi mechanics,
            GuiComponentsApi guiComponents,
            LogicApi logic) {
        this.version = Objects.requireNonNull(version, "version");
        this.registries = Objects.requireNonNull(registries, "registries");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        this.authoring = authoring;
        this.assets = assets;
        this.pipeline = pipeline;
        this.delivery = delivery;
        this.runtime = runtime;
        this.admin = admin;
        this.mechanics = mechanics;
        this.guiComponents = guiComponents;
        this.logic = logic;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public RegistryView registries() {
        return registries;
    }

    @Override
    public DiagnosticsBus diagnostics() {
        return diagnostics;
    }

    @Override
    public PlatformDescriptor platform() {
        return Objects.requireNonNull(platform.get(), "platform descriptor not resolved yet");
    }

    @Override
    public SchedulingGateway scheduling() {
        return requireWired(scheduling, "scheduling");
    }

    @Override
    public AuthoringApi authoring() {
        return requireWired(authoring, "authoring");
    }

    @Override
    public AssetApi assets() {
        return requireWired(assets, "assets");
    }

    @Override
    public PipelineApi pipeline() {
        return requireWired(pipeline, "pipeline");
    }

    @Override
    public DeliveryApi delivery() {
        return requireWired(delivery, "delivery");
    }

    @Override
    public RuntimeApi runtime() {
        return requireWired(runtime, "runtime");
    }

    @Override
    public AdminApi admin() {
        return requireWired(admin, "admin");
    }

    @Override
    public MechanicsApi mechanics() {
        return requireWired(mechanics, "mechanics");
    }

    @Override
    public LogicApi logic() {
        return requireWired(logic, "logic");
    }

    @Override
    public GuiComponentsApi guiComponents() {
        return requireWired(guiComponents, "guiComponents");
    }

    private static <T> T requireWired(T subApi, String name) {
        if (subApi == null) {
            throw new IllegalStateException(
                    "MaterioneAPI sub-api '" + name + "' is not wired yet");
        }
        return subApi;
    }
}
