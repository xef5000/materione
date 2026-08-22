package dev.materione.plugin.runtime;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.content.items.ItemDefinition;
import dev.materione.content.items.ItemType;
import dev.materione.core.content.ContentType;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.PipelineResult;
import dev.materione.core.pipeline.PipelineRunner;
import dev.materione.core.pipeline.ReloadScope;
import dev.materione.api.registry.RegistryKey;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.runtime.ContentRuntime;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.pack.allocation.AllocationTable;
import dev.materione.pack.compile.PackCompiler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.plugin.Plugin;

/**
 * The assembly's pipeline composition (plugin.md §4): parse → validate →
 * graph in {@link PipelineRunner} (core), compile in {@link PackCompiler}
 * (pack), then apply — the only stage on the main thread, via the
 * scheduling gateway's global entry. Reload re-opens the registry windows,
 * re-runs, re-freezes (D5).
 */
public final class PipelineService {

    /** Registry holding content types (D5: built-ins and addons, one path). */
    public static final RegistryKey CONTENT_TYPES = new RegistryKey("content-types");

    private final Plugin plugin;
    private final DiagnosticsBus diagnostics;
    private final QuarantineTracker quarantine;
    private final RegistryService registries;
    private final SchedulingGateway scheduling;
    private final PlatformDescriptor descriptor;
    private final PipelineRunner runner;
    private final PackCompiler compiler = new PackCompiler();
    private final ContentRuntime runtime = new ContentRuntime();
    private AllocationTable allocation;

    public PipelineService(
            Plugin plugin,
            DiagnosticsBus diagnostics,
            QuarantineTracker quarantine,
            RegistryService registries,
            SchedulingGateway scheduling,
            PlatformDescriptor descriptor) {
        this.plugin = plugin;
        this.diagnostics = diagnostics;
        this.quarantine = quarantine;
        this.registries = registries;
        this.scheduling = scheduling;
        this.descriptor = descriptor;
        this.runner = new PipelineRunner(diagnostics, quarantine);
    }

    /** Registers the built-in content types through the registry path (D5). */
    public void registerBuiltins() {
        var registry = registries.registry(CONTENT_TYPES, ContentType.class);
        ItemType items = new ItemType();
        registry.register(NamespacedId.of("materione", "item"), items, items.describe(), "materione");
    }

    public ContentRuntime runtime() {
        return runtime;
    }

    public PipelineRunner runner() {
        return runner;
    }

    public AllocationTable allocation() {
        return allocation;
    }

    /**
     * One full reload: content types stay registered (windows reopen around
     * the run), the pipeline parses/validates/graphs, the compiler stages
     * the pack, and the shadow-swap applies on the main thread. Fail-soft
     * end to end: any abort leaves the live runtime untouched (D6).
     */
    public ReloadSummary reload(java.nio.file.Path packsRoot, java.nio.file.Path generatedDir,
            java.nio.file.Path storageDir) {
        if (allocation == null) {
            allocation = AllocationTable.load(storageDir.resolve("allocation.bin"));
        }
        PipelineResult result = runner.request(new ReloadScope.Everything(), packsRoot);
        if (result == null || !result.completed()) {
            diagnostics.publish(Diagnostic.warn(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "reload skipped: another run is in flight or the run aborted"));
            return new ReloadSummary(result == null, 0, 0, 0, 0, null);
        }

        PackCompiler.Outcome outcome = compiler.compile(
                runner.lastPacks(), contentTypes(), allocation, generatedDir, diagnostics);
        String sha = outcome == null ? "" : outcome.sha256();
        boolean noop = sha.equals(runtime.livePackSha256());
        RuntimeRegistries shadow = null;
        if (!noop) {
            // the shadow is pure computation — built and counted synchronously;
            // only the swap itself hops to the global thread (plugin.md §4.5)
            shadow = buildShadow(runner.lastPacks());
            applyShadow(shadow, sha);
        }
        return new ReloadSummary(
                false,
                result.resourcesValid(),
                result.resourcesQuarantined(),
                shadow == null ? runtime.registries().itemCount() : shadow.itemCount(),
                outcome == null ? 0 : outcome.itemDefinitions(),
                noop ? null : sha);
    }

    /** Reload outcome for the operator. */
    public record ReloadSummary(
            boolean skipped, int resourcesValid, int quarantined,
            int itemsLive, int itemDefinitions, String packSha256) {}

    private void applyShadow(RuntimeRegistries shadow, String sha) {
        RuntimeRegistries previous = runtime.registries();
        String previousSha = runtime.livePackSha256();
        // the swap itself is atomic and cheap; the gateway hop is the
        // plugin.md §4.5 rule: apply runs on the global thread
        scheduling.executeGlobal(plugin, () -> {
            try {
                var report = runtime.apply(shadow, sha);
                diagnostics.publish(new Diagnostic(
                        DiagnosticCodes.SCOPE_EXPANDED, Severity.INFO,
                        "applied: +" + report.added() + " -" + report.removed()
                                + " ~" + report.changed() + " items",
                        null, null, null));
            } catch (RuntimeException e) {
                runtime.rollback(previous, previousSha);
                diagnostics.publish(new Diagnostic(
                        DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                        "apply failed, rolled back: " + e,
                        null, null, "live content unchanged; fix the reported problem and reload"));
            }
        });
    }

    private RuntimeRegistries buildShadow(List<ContentPack> packs) {
        var itemType = contentTypes().stream()
                .filter(t -> t.name().equals("item"))
                .findFirst();
        var builder = RuntimeRegistries.builder();
        if (itemType.isEmpty()) {
            return builder.build();
        }
        var templates = new LinkedHashMap<String, Map<String, Object>>();
        for (ContentPack pack : packs) {
            for (ContentResource resource : pack.resources()) {
                if ("template".equals(resource.contentType())) {
                    Object definition = resource.document().data().get(resource.id().value());
                    if (definition instanceof Map<?, ?> mapping) {
                        @SuppressWarnings("unchecked")
                        var raw = (Map<String, Object>) mapping;
                        templates.put(resource.id().toString(), raw);
                    }
                }
            }
        }
        for (ContentPack pack : packs) {
            if (pack.manifest() == null) {
                continue;
            }
            for (ContentResource resource : pack.resources()) {
                if (!"item".equals(resource.contentType())) {
                    continue;
                }
                Object definition = resource.document().data().get(resource.id().value());
                if (!(definition instanceof Map<?, ?> mapping)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                var raw = (Map<String, Object>) mapping;
                Optional<Object> parsed = itemType.get().parse(resource.id(), raw, templates);
                if (parsed.isPresent() && parsed.get() instanceof ItemDefinition item) {
                    builder.item(toRuntimeItem(item));
                }
            }
        }
        return builder.build();
    }

    private static RuntimeItem toRuntimeItem(ItemDefinition item) {
        NamespacedId modelId = null;
        if (item.wantsGeneratedModel() || item.model() != null) {
            String raw = item.wantsGeneratedModel()
                    ? item.id().value() + "_model"
                    : item.model();
            modelId = NamespacedId.of(
                    item.id().namespace(),
                    raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw);
        }
        return new RuntimeItem(
                item.id(),
                item.material(),
                modelId,
                item.displayName(),
                item.lore(),
                RuntimeItem.hashOf(item.id(), item.material(), modelId, item.displayName(), item.lore()));
    }

    private List<ContentType> contentTypes() {
        var registry = registries.registry(CONTENT_TYPES, ContentType.class);
        var out = new ArrayList<ContentType>();
        registry.entries().forEach(entry -> out.add(entry.entry()));
        return out;
    }
}
