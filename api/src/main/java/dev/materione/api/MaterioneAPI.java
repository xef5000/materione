package dev.materione.api;

import dev.materione.api.admin.AdminApi;
import dev.materione.api.assets.AssetApi;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.delivery.DeliveryApi;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.pipeline.PipelineApi;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.registry.RegistryView;
import dev.materione.api.runtime.RuntimeApi;
import dev.materione.api.scheduling.SchedulingGateway;

/**
 * The public API facade — the only surface third parties consume (api.md
 * §1: the plugin is an implementation of the API). Semver'd;
 * deprecate-then-remove, never silent breaks (master §10, robustness rule 8).
 *
 * <p>Every action — authoring content, mutating parameters, reloading the
 * pipeline, publishing packs, inspecting diagnostics, interacting with live
 * world/runtime content — is a typed operation here, actor-attributed and
 * fail-soft. Files stay canonical (D1), all mutations funnel through the one
 * pipeline (D6), and the surface is self-described for the web editor and a
 * future MCP server (D5).
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

    /** Thread hops for world/entity work (Folia-safe, D8). */
    SchedulingGateway scheduling();

    /** Content & document authoring on canonical files (D1, master §5.6). */
    AuthoringApi authoring();

    /** Binary asset management (textures, models, sounds). */
    AssetApi assets();

    /** Pipeline & validation control (D6, plugin.md §4). */
    PipelineApi pipeline();

    /** Pack hosting & release promotion (master §8.4). */
    DeliveryApi delivery();

    /** Live runtime & world operations (runtime-systems.md). */
    RuntimeApi runtime();

    /** Server health, settings, audit & editor sessions (plugin.md §5, §7.1). */
    AdminApi admin();
}
