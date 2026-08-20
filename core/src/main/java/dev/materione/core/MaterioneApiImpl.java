package dev.materione.core;

import dev.materione.api.MaterioneAPI;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.registry.RegistryView;
import dev.materione.core.registry.RegistryService;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The {@link MaterioneAPI} implementation, assembled from core services. The
 * platform descriptor arrives as a supplier because the plugin wires it after
 * the probe phase; addons only ever see the facade.
 */
public final class MaterioneApiImpl implements MaterioneAPI {

    private final String version;
    private final RegistryView registries;
    private final DiagnosticsBus diagnostics;
    private final Supplier<PlatformDescriptor> platform;

    public MaterioneApiImpl(
            String version,
            RegistryService registries,
            DiagnosticsBus diagnostics,
            Supplier<PlatformDescriptor> platform) {
        this.version = Objects.requireNonNull(version, "version");
        this.registries = Objects.requireNonNull(registries, "registries");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.platform = Objects.requireNonNull(platform, "platform");
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
}
