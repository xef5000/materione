package dev.materione.plugin.runtime;

import dev.materione.api.NamespacedId;
import dev.materione.api.gui.GuiComponent;
import dev.materione.content.gui.CompiledGui;
import dev.materione.content.gui.GuiBackgrounds;
import dev.materione.content.gui.GuiCompiler;
import dev.materione.content.gui.GuiComponents;
import dev.materione.content.gui.GuiDefinition;
import dev.materione.core.texture.PackTextures;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.content.blocks.BlockType;
import dev.materione.content.items.ItemDefinition;
import dev.materione.content.items.ItemType;
import dev.materione.content.recipes.RecipeType;
import dev.materione.plugin.runtime.behavior.BlockBehaviors;
import dev.materione.plugin.runtime.behavior.FurnitureBehaviors;
import dev.materione.plugin.runtime.behavior.ItemBehaviors;
import dev.materione.api.content.ContentType;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.PipelineResult;
import dev.materione.core.pipeline.PipelineRunner;
import dev.materione.api.pipeline.ApplyReport;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.api.pipeline.ReloadSummary;
import dev.materione.api.registry.RegistryKey;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.runtime.ContentRuntime;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.core.runtime.RuntimeRecipe;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.core.runtime.RuntimeVariables;
import dev.materione.pack.allocation.AllocationTable;
import dev.materione.pack.compile.PackCompiler;
import dev.materione.pack.compile.PackFormats;
import dev.materione.platform.recipes.RecipeApplier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
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
    public static final RegistryKey CONTENT_TYPES = RegistryKeys.CONTENT_TYPES;

    private final Plugin plugin;
    private final DiagnosticsBus diagnostics;
    private final QuarantineTracker quarantine;
    private final RegistryService registries;
    private final SchedulingGateway scheduling;
    private final PlatformDescriptor descriptor;
    private final PipelineRunner runner;
    // authored text in data files (painting titles) goes through the one pipeline
    private final PackCompiler compiler =
            new PackCompiler(dev.materione.platform.text.MaterioneText::componentJson);
    private final ContentRuntime runtime = new ContentRuntime();
    private final RecipeApplier recipeApplier = new RecipeApplier();
    /** Server-reported resource-pack format; -1 when detection failed. */
    private final int detectedPackFormat;
    /** Server-reported data-pack format (major.minor); null when detection failed. */
    private final PackFormats.Version detectedDataFormat;
    private volatile ApplyReport lastReport;
    private boolean fingerprintsLoaded;
    private AllocationTable allocation;
    private CompletableFuture<Void> applyCompletion = CompletableFuture.completedFuture(null);
    /** Live-view observers: called after each successful swap with (previous, live). */
    private final List<BiConsumer<RuntimeRegistries, RuntimeRegistries>> swapObservers = new CopyOnWriteArrayList<>();

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
        // The server's self-reported format beats the table (a server newer
        // than this build still names its own format); -1 = detection failed.
        var detected = dev.materione.platform.probe.ServerPackFormat.detect().orElse(null);
        this.detectedPackFormat = detected == null ? -1 : detected.resourceMajor();
        this.detectedDataFormat = detected == null || detected.dataMajor() <= 0
                ? null
                : new PackFormats.Version(detected.dataMajor(), detected.dataMinor());
        if (plugin != null) {
            plugin.getLogger().info("MATERIONE-PACKFORMAT detected="
                    + (detectedPackFormat < 0 ? "none" : detectedPackFormat)
                    + " advertising=" + PackFormats.FLOOR + ".."
                    + Math.max(PackFormats.MAX_KNOWN_FORMAT, detectedPackFormat)
                    + " datapack=" + (detectedDataFormat == null
                            ? "none" : detectedDataFormat.major() + "." + detectedDataFormat.minor()));
        }
    }

    /** Registers built-in content types without a platform material palette (D5). */
    public void registerBuiltins() {
        registerBuiltins(List.of());
    }

    /** Registers built-in content types with platform-supplied material choices (D5). */
    public void registerBuiltins(List<String> supportedItemMaterials) {
        var registry = registries.registry(CONTENT_TYPES, ContentType.class);
        ItemType items = new ItemType(supportedItemMaterials);
        registry.register(NamespacedId.of("materione", "item"), items, items.describe(), "materione");
        RecipeType recipes = new RecipeType();
        registry.register(NamespacedId.of("materione", "recipe"), recipes, recipes.describe(), "materione");
        BlockType blocks = new BlockType();
        registry.register(NamespacedId.of("materione", "block"), blocks, blocks.describe(), "materione");
        dev.materione.content.furniture.FurnitureType furniture =
                new dev.materione.content.furniture.FurnitureType();
        registry.register(
                NamespacedId.of("materione", "furniture"), furniture, furniture.describe(), "materione");
        dev.materione.content.glyphs.GlyphType glyphs = new dev.materione.content.glyphs.GlyphType();
        registry.register(
                NamespacedId.of("materione", "glyph"), glyphs, glyphs.describe(), "materione");
        dev.materione.content.sounds.SoundType sounds = new dev.materione.content.sounds.SoundType();
        registry.register(
                NamespacedId.of("materione", "sound"), sounds, sounds.describe(), "materione");
        dev.materione.content.paintings.PaintingType paintings =
                new dev.materione.content.paintings.PaintingType();
        registry.register(
                NamespacedId.of("materione", "painting"), paintings, paintings.describe(), "materione");
        dev.materione.content.effects.EffectType effects = new dev.materione.content.effects.EffectType();
        registry.register(
                NamespacedId.of("materione", "effect"), effects, effects.describe(), "materione");
        var variables = new dev.materione.content.variable.VariablesType();
        registry.register(
                NamespacedId.of("materione", "variable"), variables, variables.describe(), "materione");
        // GUI layer kinds through the registry path addons use (gui.md §3), then the gui type
        // that resolves its layers against them
        var components = registries.registry(RegistryKeys.GUI_COMPONENTS, GuiComponent.class);
        for (GuiComponent component : GuiComponents.builtins()) {
            components.register(NamespacedId.of("materione", component.name()),
                    component, component.describe(), "materione");
        }
        var guis = new dev.materione.content.gui.GuiType(components);
        registry.register(NamespacedId.of("materione", "gui"), guis, guis.describe(), "materione");
    }

    public ContentRuntime runtime() {
        return runtime;
    }

    /**
     * Observes every successful live swap as {@code (previous, live)}. The observer runs on the
     * global thread, after the swap and after recipes are applied, so a live view players can see
     * (open GUIs, gui.md §5) refreshes against the registries that are actually live. A failing
     * observer is reported and never rolls the apply back.
     */
    public void observeSwaps(BiConsumer<RuntimeRegistries, RuntimeRegistries> observer) {
        swapObservers.add(Objects.requireNonNull(observer, "observer"));
    }

    private void notifySwapObservers(RuntimeRegistries previous, RuntimeRegistries live) {
        for (BiConsumer<RuntimeRegistries, RuntimeRegistries> observer : swapObservers) {
            try {
                observer.accept(previous, live);
            } catch (RuntimeException e) {
                diagnostics.publish(Diagnostic.warn(
                        DiagnosticCodes.PIPELINE_STAGE_FAILED,
                        "live view refresh failed after apply: " + e));
            }
        }
    }

    /** The last pre-apply report (doctor surface); null before the first apply. */
    public ApplyReport lastReport() {
        return lastReport;
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
        return reload(new dev.materione.api.pipeline.ReloadScope.Everything(),
                packsRoot, generatedDir, storageDir);
    }

    /** Targeted variant: {@code OnePack}/{@code FileSet} scopes splice onto the last full run. */
    public synchronized ReloadSummary reload(dev.materione.api.pipeline.ReloadScope scope,
            java.nio.file.Path packsRoot, java.nio.file.Path generatedDir,
            java.nio.file.Path storageDir) {
        applyCompletion = CompletableFuture.completedFuture(null);
        if (allocation == null) {
            allocation = AllocationTable.load(storageDir.resolve("allocation.bin"));
        }
        if (!fingerprintsLoaded) {
            runner.replaceFingerprints(dev.materione.core.pipeline.FingerprintCache.load(
                    generatedDir.resolve("fingerprints.bin")));
            fingerprintsLoaded = true;
        }
        int maxAdvertised = Math.max(PackFormats.MAX_KNOWN_FORMAT, detectedPackFormat);
        if (detectedPackFormat < 0 && !PackFormats.isKnown(descriptor.minecraftVersion())) {
            // output is unchanged — the advertised range still covers
            // intermediate formats — but with neither the table nor the
            // server's own report covering this version, a server newer
            // than everything we know would reject its own pack
            diagnostics.publish(Diagnostic.warn(
                    DiagnosticCodes.PACK_FORMAT_UNKNOWN_VERSION,
                    "server version '" + descriptor.minecraftVersion()
                            + "' is not in the known pack-format table and its own pack format could "
                            + "not be detected; the pack advertises formats "
                            + PackFormats.FLOOR + ".." + maxAdvertised));
        } else if (detectedPackFormat >= 0 && !PackFormats.isKnown(descriptor.minecraftVersion())) {
            // the server named its own format — safe, but the table should
            // learn the version so future builds keep the range honest
            diagnostics.publish(Diagnostic.info(
                    DiagnosticCodes.PACK_FORMAT_UNKNOWN_VERSION,
                    "server version '" + descriptor.minecraftVersion()
                            + "' is not in the known pack-format table; using the server-reported "
                            + "format " + detectedPackFormat + " (advertising "
                            + PackFormats.FLOOR + ".." + maxAdvertised + ")"));
        }
        PipelineResult result = runner.request(scope, packsRoot);
        if (result == null || !result.completed()) {
            diagnostics.publish(Diagnostic.warn(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "reload aborted; live content unchanged"));
            return new ReloadSummary(true, false, 0, 0, 0, 0, null, null, null, null);
        }

        List<ContentPack> packs = runner.lastPacks();
        PackCompiler.Outcome outcome = compiler.compile(
                packs, contentTypes(), allocation, generatedDir, diagnostics, quarantine,
                detectedPackFormat, detectedDataFormat);
        if (outcome == null) {
            diagnostics.publish(Diagnostic.error(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "pack compilation failed; live content unchanged"));
            return new ReloadSummary(true, false, 0, 0, 0, 0, null, null, null, null);
        }
        runner.saveFingerprints(generatedDir.resolve("fingerprints.bin"));
        String sha = outcome.sha256();
        // The shadow is always built: identical pack bytes do NOT imply an
        // unchanged runtime — material-only items carry behavior/mechanics
        // that never touch pack output, and their rules compile here
        // (MTR-0021/0022). Only delivery and the swap itself are skippable.
        RuntimeRegistries shadow = buildShadow(packs);
        var preApply = runtime.diffAgainst(shadow, sha);
        boolean packUnchanged = sha.equals(runtime.livePackSha256());
        boolean noop = packUnchanged && changesNothing(preApply);
        if (!noop) {
            applyShadow(shadow, sha, preApply);
        } else {
            // Replaced registrations may produce the same data but new typed bindings.
            CompletableFuture<Void> applied = new CompletableFuture<>();
            applyCompletion = applied;
            scheduling.executeGlobal(plugin, () -> {
                try {
                    RuntimeRegistries previous = runtime.registries();
                    runtime.apply(shadow, sha);
                    notifySwapObservers(previous, shadow);
                    applied.complete(null);
                } catch (RuntimeException e) {
                    applied.completeExceptionally(e);
                }
            });
        }
        return new ReloadSummary(
                false,
                noop,
                result.resourcesValid(),
                result.resourcesQuarantined(),
                shadow.itemCount(),
                outcome.itemDefinitions(),
                packUnchanged ? null : sha,
                packUnchanged ? null : outcome.stagedZip(),
                // the datapack path rides along even on a noop: the applier
                // verifies the world folder actually holds these bytes, so a
                // lost/regenerated world gets repaired at the next reload
                outcome.stagedDatapackZip(),
                outcome.datapackSha256());
    }

    /** Called off-thread; completion includes the global-thread swap and rollback. */
    public synchronized CompletableFuture<ReloadSummary> reloadAndAwaitApply(
            ReloadScope scope, java.nio.file.Path packsRoot, java.nio.file.Path generatedDir,
            java.nio.file.Path storageDir) {
        ReloadSummary summary = reload(scope, packsRoot, generatedDir, storageDir);
        return applyCompletion.thenApply(ignored -> summary);
    }

    /** True when the pre-apply report shows nothing observable changing. */
    private static boolean changesNothing(ApplyReport report) {
        return report.added() == 0 && report.removed() == 0 && report.changed() == 0
                && report.blocksAdded() == 0 && report.blocksRemoved() == 0
                && report.furnitureAdded() == 0 && report.furnitureRemoved() == 0
                && report.recipesEnabled() == 0 && report.recipesDisabled() == 0;
    }

    private void applyShadow(
            RuntimeRegistries shadow, String sha,
            ApplyReport report) {
        // the report was computed by the caller BEFORE the swap (§4.5): what
        // this apply will do, including removals by policy and
        // restart-required changes listed separately (none can be produced
        // yet — M4 owns the producers)
        lastReport = report;
        var summary = new StringBuilder("pre-apply: ");
        report.lines(line -> {
            if (summary.length() > "pre-apply: ".length()) {
                summary.append("; ");
            }
            summary.append(line);
        });
        diagnostics.publish(new Diagnostic(
                DiagnosticCodes.SCOPE_EXPANDED, Severity.INFO, summary.toString(), null, null, null));
        // the swap itself is atomic and cheap; the gateway hop is the
        // plugin.md §4.5 rule: apply runs on the global thread
        CompletableFuture<Void> applied = new CompletableFuture<>();
        applyCompletion = applied;
        scheduling.executeGlobal(plugin, () -> {
            RuntimeRegistries previous = runtime.registries();
            String previousSha = runtime.livePackSha256();
            try {
                runtime.apply(shadow, sha);
                var recipes = recipeApplier.apply(shadow);
                diagnostics.publish(new Diagnostic(
                        DiagnosticCodes.SCOPE_EXPANDED, Severity.INFO,
                        "applied: +" + report.added() + " -" + report.removed()
                                + " ~" + report.changed() + " items, "
                                + recipes.registered() + " recipe(s)",
                        null, null, null));
            } catch (RuntimeException e) {
                runtime.rollback(previous, previousSha);
                diagnostics.publish(new Diagnostic(
                        DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                        "apply failed, rolled back: " + e,
                        null, null, "live content unchanged; fix the reported problem and reload"));
                applied.completeExceptionally(e);
                return;
            }
            // only a swap that stuck reaches the live views: a rolled-back apply shows nothing new
            notifySwapObservers(previous, shadow);
            applied.complete(null);
        });
    }

    RuntimeRegistries buildShadow(List<ContentPack> packs) {
        var itemType = contentTypes().stream().filter(t -> t.name().equals("item")).findFirst();
        var recipeType = contentTypes().stream().filter(t -> t.name().equals("recipe")).findFirst();
        var blockType = contentTypes().stream().filter(t -> t.name().equals("block")).findFirst();
        var furnitureType = contentTypes().stream().filter(t -> t.name().equals("furniture")).findFirst();
        var guiType = contentTypes().stream().filter(t -> t.name().equals("gui")).findFirst();
        var guiModels = new LinkedHashMap<NamespacedId, CompiledGui>();
        var glyphType = contentTypes().stream().filter(t -> t.name().equals("glyph")).findFirst();
        var effectType = contentTypes().stream().filter(t -> t.name().equals("effect")).findFirst();
        var builder = RuntimeRegistries.builder();
        var behaviors = new LinkedHashMap<NamespacedId, List<dev.materione.behavior.rule.Rule>>();
        var mechanicAvailability = new LinkedHashMap<String, java.util.function.BooleanSupplier>();
        var blockBehaviors = new LinkedHashMap<NamespacedId, List<dev.materione.behavior.rule.Rule>>();
        var furnitureBehaviors = new LinkedHashMap<NamespacedId, List<dev.materione.behavior.rule.Rule>>();
        var itemMechanics = new LinkedHashMap<NamespacedId, List<dev.materione.plugin.runtime.mechanics.ActiveMechanic>>();
        var runtimeBlocks = dev.materione.core.runtime.RuntimeBlocks.builder();
        var runtimeFurnitures = dev.materione.core.runtime.RuntimeFurnitures.builder();
        var glyphEntries = new LinkedHashMap<NamespacedId, dev.materione.core.runtime.RuntimeGlyphs.Entry>();
        var effectEntries = new LinkedHashMap<NamespacedId, dev.materione.core.runtime.RuntimeEffects.Entry>();
        if (itemType.isEmpty()) {
            builder.view(ItemBehaviors.KEY, new ItemBehaviors(behaviors));
            builder.view(BlockBehaviors.KEY, BlockBehaviors.empty());
            builder.view(FurnitureBehaviors.KEY, FurnitureBehaviors.empty());
            builder.view(dev.materione.plugin.runtime.mechanics.ItemMechanics.KEY,
                    dev.materione.plugin.runtime.mechanics.ItemMechanics.empty());
            builder.view(dev.materione.core.runtime.RuntimeBlocks.KEY, runtimeBlocks.build());
            builder.view(dev.materione.core.runtime.RuntimeFurnitures.KEY, runtimeFurnitures.build());
            builder.view(dev.materione.core.runtime.RuntimeGlyphs.KEY,
                    new dev.materione.core.runtime.RuntimeGlyphs(glyphEntries));
            builder.view(RuntimeGuis.KEY, RuntimeGuis.empty());
            builder.view(RuntimeVariables.KEY, RuntimeVariables.empty());
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
        // pre-pass: the declared variables first, so a set_var in any rule or flow compiles against
        // the whole shadow whatever order the resources iterate in (logic.md §3.2)
        var declaredVariables = parseVariables(packs, templates);
        this.declaredVariables = declaredVariables;
        builder.view(RuntimeVariables.KEY, new RuntimeVariables(declaredVariables));
        // pre-pass: parse every item once — the block/furniture branches need
        // the live item ids to decide implicit vs synthetic placement links,
        // whatever order the resources iterate in
        var parsedItems = new LinkedHashMap<NamespacedId, dev.materione.content.items.ItemDefinition>();
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
                try {
                    Optional<Object> parsed = itemType.get().parse(resource.id(), raw, templates);
                    if (parsed.isPresent() && parsed.get() instanceof ItemDefinition item) {
                        parsedItems.put(item.id(), item);
                    }
                } catch (RuntimeException e) {
                    // mirror the compiler's containment: a throwing parser
                    // skips the resource, never the run (D6)
                    diagnostics.publish(Diagnostic.error(
                            DiagnosticCodes.PIPELINE_STAGE_FAILED,
                            "shadow build skipped " + resource.id() + ": " + e));
                }
            }
        }
        for (ContentPack pack : packs) {
            if (pack.manifest() == null) {
                continue;
            }
            for (ContentResource resource : pack.resources()) {
                Object definition = resource.document().data().get(resource.id().value());
                if (!(definition instanceof Map<?, ?> mapping)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                var raw = (Map<String, Object>) mapping;
                if ("item".equals(resource.contentType())) {
                    ItemDefinition item = parsedItems.get(resource.id());
                    if (item != null) {
                        List<dev.materione.plugin.runtime.mechanics.ActiveMechanic> active =
                                mechanicsResolver().resolve(item.id(), item.raw().get("mechanics"), (name, reason) ->
                                        diagnostics.publish(new Diagnostic(
                                                DiagnosticCodes.UNKNOWN_MECHANIC, dev.materione.api.diagnostics.Severity.WARN,
                                                "mechanic '" + name + "' on " + item.id() + ": " + reason,
                                                item.id(), null,
                                                "check the name and parameters against the registry self-description")));
                        builder.item(toRuntimeItem(item, active));
                        compileBehaviors(item, active, behaviors, mechanicAvailability);
                        itemMechanics.put(item.id(), active);
                    }
                } else if ("recipe".equals(resource.contentType()) && recipeType.isPresent()) {
                    Optional<Object> parsed;
                    try {
                        parsed = recipeType.get().parse(resource.id(), raw, templates);
                    } catch (RuntimeException e) {
                        diagnostics.publish(Diagnostic.error(
                                DiagnosticCodes.PIPELINE_STAGE_FAILED,
                                "shadow build skipped " + resource.id() + ": " + e));
                        continue;
                    }
                    if (parsed.isPresent() && parsed.get() instanceof RecipeType.Recipe recipe) {
                        builder.recipe(new RuntimeRecipe(
                                recipe.id(), recipe.kind(), recipe.result(), recipe.shape(),
                                recipe.shapedIngredients(), recipe.ingredients(),
                                recipe.experience(), recipe.cookingTime()));
                    }
                } else if ("block".equals(resource.contentType()) && blockType.isPresent()) {
                    Optional<Object> parsed;
                    try {
                        parsed = blockType.get().parse(resource.id(), raw, templates);
                    } catch (RuntimeException e) {
                        diagnostics.publish(Diagnostic.error(
                                DiagnosticCodes.PIPELINE_STAGE_FAILED,
                                "shadow build skipped " + resource.id() + ": " + e));
                        continue;
                    }
                    if (parsed.isPresent() && parsed.get() instanceof dev.materione.content.blocks.BlockDefinition block) {
                        String host = compileBlock(block, runtimeBlocks, blockBehaviors);
                        if (host != null) {
                            linkPlacement(block.id(), block.placedBy(), block.modelRef(),
                                    block.displayName(), placementMaterial(block),
                                    parsedItems.keySet(), builder, runtimeBlocks::placedBy);
                        }
                    }
                } else if ("glyph".equals(resource.contentType()) && glyphType.isPresent()) {
                    Optional<Object> parsed;
                    try {
                        parsed = glyphType.get().parse(resource.id(), raw, templates);
                    } catch (RuntimeException e) {
                        diagnostics.publish(Diagnostic.error(
                                DiagnosticCodes.PIPELINE_STAGE_FAILED,
                                "shadow build skipped " + resource.id() + ": " + e));
                        continue;
                    }
                    if (parsed.isPresent() && parsed.get() instanceof dev.materione.content.glyphs.GlyphDefinition glyph) {
                        dev.materione.core.runtime.RuntimeGlyphs.Entry entry = toGlyphEntry(glyph);
                        if (entry != null) {
                            glyphEntries.put(glyph.id(), entry);
                        }
                    }
                } else if ("effect".equals(resource.contentType()) && effectType.isPresent()) {
                    Optional<Object> parsed;
                    try {
                        parsed = effectType.get().parse(resource.id(), raw, templates);
                    } catch (RuntimeException e) {
                        diagnostics.publish(Diagnostic.error(
                                DiagnosticCodes.PIPELINE_STAGE_FAILED,
                                "shadow build skipped " + resource.id() + ": " + e));
                        continue;
                    }
                    if (parsed.isPresent()
                            && parsed.get() instanceof dev.materione.content.effects.EffectDefinition effect) {
                        // the signal comes from the durable table — the compile
                        // stage claimed it this run (D7); missing → skipped
                        Optional<String> signal = allocation == null
                                ? Optional.empty()
                                : allocation.allocationOf(
                                        effect.id(), dev.materione.content.effects.EffectType.ALLOCATION_KIND);
                        if (signal.isPresent()) {
                            try {
                                int slot = Integer.parseInt(signal.get());
                                effectEntries.put(effect.id(),
                                        new dev.materione.core.runtime.RuntimeEffects.Entry(
                                                effect.id(), effect.kind(), effect.speed(),
                                                effect.amplitude(),
                                                0xFF0000 | (0xFF - slot)));
                            } catch (NumberFormatException e) {
                                diagnostics.publish(Diagnostic.warn(
                                        DiagnosticCodes.PIPELINE_STAGE_FAILED,
                                        "shadow build skipped " + effect.id()
                                                + ": corrupt signal allocation"));
                            }
                        } else {
                            diagnostics.publish(Diagnostic.warn(
                                    DiagnosticCodes.PIPELINE_STAGE_FAILED,
                                    "shadow build skipped " + effect.id() + ": no allocated signal"));
                        }
                    }
                } else if ("furniture".equals(resource.contentType()) && furnitureType.isPresent()) {
                    Optional<Object> parsed;
                    try {
                        parsed = furnitureType.get().parse(resource.id(), raw, templates);
                    } catch (RuntimeException e) {
                        diagnostics.publish(Diagnostic.error(
                                DiagnosticCodes.PIPELINE_STAGE_FAILED,
                                "shadow build skipped " + resource.id() + ": " + e));
                        continue;
                    }
                    if (parsed.isPresent()
                            && parsed.get() instanceof dev.materione.content.furniture.FurnitureDefinition furniture) {
                        compileFurniture(furniture, runtimeFurnitures, furnitureBehaviors);
                        linkPlacement(furniture.id(), furniture.placedBy(), furniture.modelRef(),
                                furniture.displayName(), "PAPER",
                                parsedItems.keySet(), builder, runtimeFurnitures::placedBy);
                    }
                } else if ("gui".equals(resource.contentType()) && guiType.isPresent()) {
                    compileGuiModel(pack, resource, raw, templates, guiType.get(), guiModels);
                }
            }
        }
        builder.view(ItemBehaviors.KEY, new ItemBehaviors(behaviors, mechanicAvailability));
        builder.view(BlockBehaviors.KEY, new BlockBehaviors(blockBehaviors));
        builder.view(FurnitureBehaviors.KEY, new FurnitureBehaviors(furnitureBehaviors));
        builder.view(dev.materione.plugin.runtime.mechanics.ItemMechanics.KEY,
                new dev.materione.plugin.runtime.mechanics.ItemMechanics(itemMechanics));
        builder.view(dev.materione.core.runtime.RuntimeBlocks.KEY, runtimeBlocks.build());
        builder.view(dev.materione.core.runtime.RuntimeFurnitures.KEY, runtimeFurnitures.build());
        builder.view(dev.materione.core.runtime.RuntimeGlyphs.KEY,
                new dev.materione.core.runtime.RuntimeGlyphs(glyphEntries));
        builder.view(dev.materione.core.runtime.RuntimeEffects.KEY,
                new dev.materione.core.runtime.RuntimeEffects(effectEntries));
        // GUI slots resolve against every item of this apply, placement items included (gui.md §5)
        builder.view(RuntimeGuis.KEY, RuntimeGuis.build(
                guiModels, builder::findItem, RuntimeGuis::vanillaMaterial, diagnostics::publish));
        return builder.build();
    }

    /**
     * One GUI's compiled model for the live view (gui.md §5), through the same
     * {@link GuiCompiler} the pack compile ran: the GUI's durable background codepoint and its own
     * pack's textures. The pack compile has already reported this GUI's diagnostics, so they are
     * not published a second time here. A GUI with no allocated codepoint is not live.
     */
    private void compileGuiModel(
            ContentPack pack, ContentResource resource, Map<String, Object> raw,
            Map<String, Map<String, Object>> templates, ContentType guiType,
            Map<NamespacedId, CompiledGui> out) {
        Optional<Object> parsed;
        try {
            parsed = guiType.parse(resource.id(), raw, templates);
        } catch (RuntimeException e) {
            diagnostics.publish(Diagnostic.error(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "shadow build skipped " + resource.id() + ": " + e));
            return;
        }
        if (parsed.isEmpty() || !(parsed.get() instanceof GuiDefinition gui)) {
            return;
        }
        Optional<String> allocated = allocation == null
                ? Optional.empty()
                : allocation.allocationOf(gui.id(), GuiBackgrounds.ALLOCATION_KIND);
        if (allocated.isEmpty()) {
            diagnostics.publish(Diagnostic.warn(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "shadow build skipped " + gui.id() + ": no allocated background codepoint"));
            return;
        }
        int number = dev.materione.api.content.AllocatedRange.parse(allocated.get()).start();
        if (!GuiBackgrounds.fits(number)) {
            return; // exhausted: the pack compile quarantined this GUI with MTR-0053
        }
        try {
            out.put(gui.id(), GuiCompiler.compile(
                    gui, GuiBackgrounds.codepoint(number),
                    new PackTextures(pack.id(), pack.directory())).model());
        } catch (RuntimeException e) {
            diagnostics.publish(Diagnostic.error(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "shadow build skipped " + gui.id() + ": " + e));
        }
    }

    /**
     * One parsed glyph as the runtime sees it — the compiler's allocation is
     * the codepoint truth (D7); a glyph whose run is missing (compiled while
     * quarantined, or a fresh table) is skipped with a warning, never guessed.
     */
    private dev.materione.core.runtime.RuntimeGlyphs.Entry toGlyphEntry(
            dev.materione.content.glyphs.GlyphDefinition glyph) {
        int[] codepoints = new int[0];
        if (!glyph.isReference()) {
            Optional<String> value = allocation == null
                    ? Optional.empty()
                    : allocation.allocationOf(
                            glyph.id(), dev.materione.content.glyphs.GlyphType.ALLOCATION_KIND);
            if (value.isEmpty()) {
                diagnostics.publish(Diagnostic.warn(
                        DiagnosticCodes.PIPELINE_STAGE_FAILED,
                        "shadow build skipped " + glyph.id() + ": no allocated codepoint"));
                return null;
            }
            var run = dev.materione.api.content.AllocatedRange.parse(value.get());
            codepoints = new int[run.count()];
            for (int i = 0; i < run.count(); i++) {
                codepoints[i] = dev.materione.core.text.GlyphCodepoints.of(run.start() + i);
            }
        }
        return new dev.materione.core.runtime.RuntimeGlyphs.Entry(
                glyph.id(),
                glyph.fontKey(),
                codepoints,
                glyph.placeholders(),
                glyph.permission(),
                glyph.emoji(),
                glyph.tabComplete(),
                glyph.shadowColor(),
                glyph.reference(),
                glyph.indexSpec());
    }

    /**
     * Turns one parsed block definition into its runtime form. The state slot
     * comes from the durable allocation table — the compiler claimed it during
     * this run's compile stage, and slots never shift (D7). Definitions whose
     * slot is missing or out of range are diagnosed and skipped (null host).
     * Returns the resolved host id so the caller can link the placement item.
     */
    private String compileBlock(
            dev.materione.content.blocks.BlockDefinition block,
            dev.materione.core.runtime.RuntimeBlocks.Builder runtimeBlocks,
            Map<NamespacedId, List<dev.materione.behavior.rule.Rule>> blockBehaviors) {
        String host = dev.materione.core.blocks.BlockStateSpaces.hostOf(
                block.technique(), block.host());
        int slot = -1;
        if (dev.materione.core.blocks.BlockStateSpaces.allocatesState(block.technique())) {
            Optional<String> value = allocation == null
                    ? Optional.empty()
                    : allocation.allocationOf(
                            block.id(), dev.materione.content.blocks.BlockType.ALLOCATION_KIND_PREFIX + host);
            if (value.isEmpty()) {
                diagnostics.publish(Diagnostic.warn(
                        DiagnosticCodes.PIPELINE_STAGE_FAILED,
                        "shadow build skipped " + block.id() + ": no allocated state slot"));
                return null;
            }
            slot = Integer.parseInt(value.get());
            if (slot >= dev.materione.core.blocks.BlockStateSpaces.capacity(block.technique())) {
                diagnostics.publish(Diagnostic.warn(
                        DiagnosticCodes.BLOCK_STATE_EXHAUSTED,
                        "shadow build skipped " + block.id() + ": slot " + slot + " outside "
                                + host + " space"));
                return null;
            }
        }
        runtimeBlocks.block(new dev.materione.core.runtime.RuntimeBlock(
                block.id(), block.technique(), host, slot,
                block.drops(), block.blastResistant(), block.movePolicy()));
        if (!block.behavior().isEmpty()) {
            dev.materione.behavior.rule.ParsedRules parsedRules =
                    dev.materione.behavior.flow.FlowCompiler.compile(
                            block.id(), block.behavior(), block.raw().get("flows"), logicLookup());
            parsedRules.problems().forEach(diagnostics::publish);
            if (!parsedRules.isEmpty()) {
                blockBehaviors.put(block.id(), parsedRules.rules());
            }
        }
        return host;
    }

    /**
     * Turns one parsed furniture definition into its runtime form — model
     * ref resolved by the same {@code <id>_model} convention the compiler
     * emits, behavior rules compiled fail-soft.
     */
    private void compileFurniture(
            dev.materione.content.furniture.FurnitureDefinition furniture,
            dev.materione.core.runtime.RuntimeFurnitures.Builder runtimeFurnitures,
            Map<NamespacedId, List<dev.materione.behavior.rule.Rule>> furnitureBehaviors) {
        String modelRef = furniture.modelRef();
        runtimeFurnitures.furniture(new dev.materione.core.runtime.RuntimeFurniture(
                furniture.id(),
                modelRef,
                furniture.displayName(),
                furniture.rotationSnap(),
                furniture.hitboxes().stream()
                        .map(hitbox -> new dev.materione.core.runtime.RuntimeFurniture.Hitbox(
                                hitbox.type(), hitbox.offset(), hitbox.width(), hitbox.height()))
                        .toList(),
                furniture.seats(),
                furniture.lights().stream()
                        .map(light -> new dev.materione.core.runtime.RuntimeFurniture.Light(
                                light.offset(), light.level()))
                        .toList(),
                furniture.storage(),
                furniture.label(),
                furniture.evolution().stream()
                        .map(stage -> new dev.materione.core.runtime.RuntimeFurniture.Stage(
                                stage.target(), stage.afterSeconds()))
                        .toList()));
        if (!furniture.behavior().isEmpty()) {
            dev.materione.behavior.rule.ParsedRules parsedRules =
                    dev.materione.behavior.flow.FlowCompiler.compile(
                            furniture.id(), furniture.behavior(), furniture.raw().get("flows"), logicLookup());
            parsedRules.problems().forEach(diagnostics::publish);
            if (!parsedRules.isEmpty()) {
                furnitureBehaviors.put(furniture.id(), parsedRules.rules());
            }
        }
    }

    /**
     * The synthetic block item's material: the host when vanilla has an item
     * form for it (note block, chorus plant, shaped hosts, most technical
     * hosts — placing rides BlockPlaceEvent), string for the tripwire
     * technique (vanilla parity), paper otherwise — hosts like tripwire have
     * no item form, and a non-item material would crash every stack factory.
     * Anything that is not a block material places through the interact
     * fallback (CustomBlockService).
     */
    private static String placementMaterial(dev.materione.content.blocks.BlockDefinition block) {
        if (block.technique() == dev.materione.core.blocks.BlockStateSpaces.Technique.STRING) {
            return "STRING";
        }
        String host = dev.materione.core.blocks.BlockStateSpaces.hostOf(
                block.technique(), block.host()).toUpperCase(java.util.Locale.ROOT);
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(host);
        try {
            return material != null && material.isItem() ? material.name() : "PAPER";
        } catch (RuntimeException | LinkageError e) {
            // isItem reads the server's item registry; without one (headless
            // recording of the editor demo snapshot) fall back like a non-item
            return "PAPER";
        }
    }

    /**
     * Registers one block/furniture's placement link (runtime-systems §13).
     * The explicit {@code item:} reference wins; otherwise a live item with
     * the same id is implicitly the placement item; otherwise the placement
     * item is synthesized into the items registry (host material for blocks,
     * paper for furniture) — every placeable definition is grabbable without
     * authoring a separate item first.
     */
    private static void linkPlacement(
            NamespacedId id,
            NamespacedId explicitItem,
            String modelRef,
            String displayName,
            String defaultMaterial,
            java.util.Set<NamespacedId> liveItemIds,
            RuntimeRegistries.Builder builder,
            java.util.function.BiConsumer<NamespacedId, NamespacedId> placedBy) {
        if (explicitItem != null) {
            placedBy.accept(explicitItem, id);
            return;
        }
        if (liveItemIds.contains(id)) {
            placedBy.accept(id, id); // implicit same-id placement facet
            return;
        }
        NamespacedId modelId = modelRef == null ? null : NamespacedId.parse(modelRef);
        String name = displayName != null && !displayName.isBlank()
                ? displayName : humanize(id.value());
        builder.item(new RuntimeItem(
                id,
                defaultMaterial,
                modelId,
                name,
                List.of(),
                RuntimeItem.hashOf(id, defaultMaterial, modelId, name, List.of()),
                null,
                true,
                null));
        placedBy.accept(id, id);
    }

    /** {@code amethyst_lamp} → {@code Amethyst Lamp} (synthetic-item tooltips). */
    private static String humanize(String value) {
        var out = new StringBuilder();
        for (String word : value.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /**
     * The registries rules resolve their actions and triggers against, read at each compile so an
     * addon registration is seen by the next reload (logic.md §4).
     */
    /** The declarations of the shadow under construction, read by the rule compiler (logic.md §3.2). */
    private volatile Map<NamespacedId, dev.materione.api.logic.variable.VariableDeclaration> declaredVariables =
            Map.of();

    /**
     * Parses every {@code variable} resource of the packs into its declaration. A definition that does
     * not parse is skipped here and quarantined by the pipeline like any other resource.
     */
    private Map<NamespacedId, dev.materione.api.logic.variable.VariableDeclaration> parseVariables(
            List<ContentPack> packs, Map<String, Map<String, Object>> templates) {
        var declared = new LinkedHashMap<NamespacedId, dev.materione.api.logic.variable.VariableDeclaration>();
        var variableType = contentTypes().stream().filter(t -> t.name().equals("variable")).findFirst();
        if (variableType.isEmpty()) {
            return declared;
        }
        for (ContentPack pack : packs) {
            if (pack.manifest() == null) {
                continue;
            }
            for (ContentResource resource : pack.resources()) {
                if (!"variable".equals(resource.contentType())) {
                    continue;
                }
                Object definition = resource.document().data().get(resource.id().value());
                if (!(definition instanceof Map<?, ?> mapping)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                var raw = (Map<String, Object>) mapping;
                try {
                    Optional<Object> parsed = variableType.get().parse(resource.id(), raw, templates);
                    if (parsed.isPresent() && parsed.get()
                            instanceof dev.materione.api.logic.variable.VariableDeclaration declaration) {
                        declared.putIfAbsent(declaration.id(), declaration);
                    }
                } catch (RuntimeException e) {
                    diagnostics.publish(Diagnostic.error(
                            DiagnosticCodes.PIPELINE_STAGE_FAILED,
                            "shadow build skipped " + resource.id() + ": " + e));
                }
            }
        }
        return declared;
    }

    private dev.materione.behavior.logic.LogicLookup logicLookup() {
        // the declarations of the shadow being built (set by the pre-pass); a flow compiled outside a build sees none
        Map<NamespacedId, dev.materione.api.logic.variable.VariableDeclaration> declared = declaredVariables;
        return new dev.materione.behavior.logic.RegistryLogicLookup(registries, id -> Optional.ofNullable(declared.get(id)));
    }

    /**
     * Compiles one item's {@code behavior:} section into engine rules
     * (runtime-systems §2). Broken rules are diagnosed (MTR-0021/0022) and
     * skipped — their siblings still load; a single action the registry does
     * not know, or whose type rejects its parameters, is skipped alone
     * (MTR-0057).
     */
    private void compileBehaviors(
            ItemDefinition item,
            List<dev.materione.plugin.runtime.mechanics.ActiveMechanic> mechanics,
            Map<NamespacedId, List<dev.materione.behavior.rule.Rule>> behaviors,
            Map<String, java.util.function.BooleanSupplier> availability) {
        var compiled = new ArrayList<dev.materione.behavior.rule.Rule>();
        // the rules the author wrote, with the item's own named flows (logic.md §2.4)
        if (!item.behavior().isEmpty()) {
            try {
                var parsed = dev.materione.behavior.flow.FlowCompiler.compile(
                        item.id(), item.behavior(), item.raw().get("flows"), logicLookup());
                parsed.problems().forEach(diagnostics::publish);
                compiled.addAll(parsed.rules());
            } catch (RuntimeException failure) {
                diagnostics.publish(new Diagnostic(
                        DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN,
                        item.id() + " behavior: " + failure,
                        item.id(), null, "fix the behavior section; its rules were skipped"));
            }
        }
        // then the rules its mechanics generate: not in the file, so no field target
        int bindingIndex = 0;
        for (var mechanic : mechanics) {
            int index = 0;
            for (var rule : mechanic.behaviorRules()) {
                var scoped = new LinkedHashMap<>(rule);
                String ruleId = item.id() + "/mechanic/" + mechanic.id() + "/" + bindingIndex + "/" + index++;
                scoped.put("id", ruleId);
                availability.put(ruleId, mechanic::isAvailable);
                try {
                    var parsed = dev.materione.behavior.flow.FlowCompiler.compileGenerated(
                            item.id(), List.of(scoped), logicLookup());
                    parsed.problems().forEach(diagnostics::publish);
                    compiled.addAll(parsed.rules());
                } catch (RuntimeException failure) {
                    diagnostics.publish(new Diagnostic(
                            DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN,
                            item.id() + " rule '" + ruleId + "': " + failure,
                            item.id(), null, "fix the rule parameters; only this rule was skipped"));
                }
            }
            bindingIndex++;
        }
        if (!compiled.isEmpty()) {
            behaviors.put(item.id(), List.copyOf(compiled));
        }
    }

    private static RuntimeItem toRuntimeItem(
            ItemDefinition item,
            List<dev.materione.plugin.runtime.mechanics.ActiveMechanic> mechanics) {
        NamespacedId modelId = null;
        if (item.wantsGeneratedModel() || item.model() != null) {
            String raw = item.wantsGeneratedModel()
                    ? item.id().value() + "_model"
                    : item.model();
            modelId = NamespacedId.of(
                    item.id().namespace(),
                    raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw);
        }
        var runtimeState = new LinkedHashMap<String, Object>();
        runtimeState.put("behavior", item.behavior());
        runtimeState.put("mechanics", mechanics.stream().map(mechanic ->
                Map.of("id", mechanic.id().toString(), "params", mechanic.params(),
                        "rules", mechanic.behaviorRules(), "generation", mechanic.generation())).toList());
        runtimeState.put("jukebox_song", item.jukeboxSong());
        return new RuntimeItem(
                item.id(),
                item.material(),
                modelId,
                item.displayName(),
                item.lore(),
                RuntimeItem.hashOf(item.id(), item.material(), modelId, item.displayName(), item.lore(), runtimeState),
                null, false,
                item.jukeboxSong(),
                mechanics.stream().flatMap(mechanic -> mechanic.itemModifiers().stream()).toList());
    }

    /** Resolves mechanics against the frozen-at-apply registry snapshot. */
    private dev.materione.plugin.runtime.mechanics.MechanicsResolver mechanicsResolver() {
        return new dev.materione.plugin.runtime.mechanics.MechanicsResolver(
                registries.byKey(
                        dev.materione.plugin.runtime.mechanics.MechanicsResolver.REGISTRY_KEY,
                        dev.materione.api.mechanics.Mechanic.class));
    }

    public List<ContentType> contentTypes() {
        var registry = registries.registry(CONTENT_TYPES, ContentType.class);
        var out = new ArrayList<ContentType>();
        registry.entries().forEach(entry -> out.add(entry.entry()));
        return out;
    }
}
