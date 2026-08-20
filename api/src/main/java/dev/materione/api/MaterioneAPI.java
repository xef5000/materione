package dev.materione.api;

import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.registry.RegistryView;

/**
 * The public API facade — the only surface third parties consume. Semver'd;
 * deprecate-then-remove, never silent breaks (master §10, robustness rule 8).
 * At M0 it exposes version, registries, diagnostics, and the platform
 * descriptor; it grows with each milestone.
 */
public interface MaterioneAPI {

    /** Plugin version (Gradle-injected, e.g. {@code 0.1.0-SNAPSHOT}). */
    String version();

    /** Read view over all registries. */
    RegistryView registries();

    /** The diagnostics bus (publish and read — addons may emit diagnostics). */
    DiagnosticsBus diagnostics();

    /** The resolved platform descriptor for this server. */
    PlatformDescriptor platform();
}
