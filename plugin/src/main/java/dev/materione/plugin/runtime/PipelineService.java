package dev.materione.plugin.runtime;

import dev.materione.api.NamespacedId;
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
import dev.materione.core.runtime.RuntimeRecipe;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.pack.allocation.AllocationTable;
import dev.materione.pack.compile.PackCompiler;
import dev.materione.pack.compile.PackFormats;
import dev.materione.platform.recipes.RecipeApplier;
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
    private final RecipeApplier recipeApplier = new RecipeApplier();
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
        RecipeType recipes = new RecipeType();
        registry.register(NamespacedId.of("materione", "recipe"), recipes, recipes.describe(), "materione");
        BlockType blocks = new BlockType();
        registry.register(NamespacedId.of("materione", "block"), blocks, blocks.describe(), "materione");
        dev.materione.content.furniture.FurnitureType furniture =
                new dev.materione.content.furniture.FurnitureType();
        registry.register(
                NamespacedId.of("materione", "furniture"), furniture, furniture.describe(), "materione");
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
        if (!PackFormats.isKnown(descriptor.minecraftVersion())) {
            // output is unchanged — the advertised range still covers
            // intermediate formats — but a server newer than everything we
            // know would reject its own pack until the table is raised
            diagnostics.publish(Diagnostic.warn(
                    DiagnosticCodes.PACK_FORMAT_UNKNOWN_VERSION,
                    "server version '" + descriptor.minecraftVersion()
                            + "' is not in the known pack-format table; the pack advertises formats "
                            + PackFormats.FLOOR + ".." + PackFormats.MAX_KNOWN_FORMAT));
        }
        PipelineResult result = runner.request(new ReloadScope.Everything(), packsRoot);
        if (result == null || !result.completed()) {
            diagnostics.publish(Diagnostic.warn(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "reload skipped: another run is in flight or the run aborted"));
            return new ReloadSummary(result == null, 0, 0, 0, 0, null, null);
        }

        PackCompiler.Outcome outcome = compiler.compile(
                runner.lastPacks(), contentTypes(), allocation, generatedDir, diagnostics, quarantine);
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
                noop ? null : sha,
                noop ? null : outcome.stagedZip());
    }

    /** Reload outcome for the operator. */
    public record ReloadSummary(
            boolean skipped, int resourcesValid, int quarantined,
            int itemsLive, int itemDefinitions, String packSha256, java.nio.file.Path packZip) {}

    private void applyShadow(RuntimeRegistries shadow, String sha) {
        RuntimeRegistries previous = runtime.registries();
        String previousSha = runtime.livePackSha256();
        // the swap itself is atomic and cheap; the gateway hop is the
        // plugin.md §4.5 rule: apply runs on the global thread
        scheduling.executeGlobal(plugin, () -> {
            try {
                var report = runtime.apply(shadow, sha);
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
            }
        });
    }

    private RuntimeRegistries buildShadow(List<ContentPack> packs) {
        var itemType = contentTypes().stream().filter(t -> t.name().equals("item")).findFirst();
        var recipeType = contentTypes().stream().filter(t -> t.name().equals("recipe")).findFirst();
        var blockType = contentTypes().stream().filter(t -> t.name().equals("block")).findFirst();
        var furnitureType = contentTypes().stream().filter(t -> t.name().equals("furniture")).findFirst();
        var builder = RuntimeRegistries.builder();
        var behaviors = new LinkedHashMap<NamespacedId, List<dev.materione.behavior.rule.Rule>>();
        var blockBehaviors = new LinkedHashMap<NamespacedId, List<dev.materione.behavior.rule.Rule>>();
        var furnitureBehaviors = new LinkedHashMap<NamespacedId, List<dev.materione.behavior.rule.Rule>>();
        var itemMechanics = new LinkedHashMap<NamespacedId, List<dev.materione.plugin.runtime.mechanics.ActiveMechanic>>();
        var runtimeBlocks = dev.materione.core.runtime.RuntimeBlocks.builder();
        var runtimeFurnitures = dev.materione.core.runtime.RuntimeFurnitures.builder();
        if (itemType.isEmpty()) {
            builder.view(ItemBehaviors.KEY, new ItemBehaviors(behaviors));
            builder.view(BlockBehaviors.KEY, BlockBehaviors.empty());
            builder.view(FurnitureBehaviors.KEY, FurnitureBehaviors.empty());
            builder.view(dev.materione.plugin.runtime.mechanics.ItemMechanics.KEY,
                    dev.materione.plugin.runtime.mechanics.ItemMechanics.empty());
            builder.view(dev.materione.core.runtime.RuntimeBlocks.KEY, runtimeBlocks.build());
            builder.view(dev.materione.core.runtime.RuntimeFurnitures.KEY, runtimeFurnitures.build());
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
                Object definition = resource.document().data().get(resource.id().value());
                if (!(definition instanceof Map<?, ?> mapping)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                var raw = (Map<String, Object>) mapping;
                if ("item".equals(resource.contentType())) {
                    Optional<Object> parsed;
                    try {
                        parsed = itemType.get().parse(resource.id(), raw, templates);
                    } catch (RuntimeException e) {
                        // mirror the compiler's containment: a throwing parser
                        // skips the resource, never the run (D6)
                        diagnostics.publish(Diagnostic.error(
                                DiagnosticCodes.PIPELINE_STAGE_FAILED,
                                "shadow build skipped " + resource.id() + ": " + e));
                        continue;
                    }
                    if (parsed.isPresent() && parsed.get() instanceof ItemDefinition item) {
                        List<dev.materione.plugin.runtime.mechanics.ActiveMechanic> active =
                                mechanicsResolver().resolve(item.id(), item.mechanics(), (name, reason) ->
                                        diagnostics.publish(new Diagnostic(
                                                DiagnosticCodes.UNKNOWN_MECHANIC, dev.materione.api.diagnostics.Severity.WARN,
                                                "mechanic '" + name + "' on " + item.id() + ": " + reason,
                                                item.id(), null,
                                                "check the name and parameters against the registry self-description")));
                        builder.item(toRuntimeItem(item, active));
                        compileBehaviors(item, behaviors);
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
                        compileBlock(block, runtimeBlocks, blockBehaviors);
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
                    }
                }
            }
        }
        builder.view(ItemBehaviors.KEY, new ItemBehaviors(behaviors));
        builder.view(BlockBehaviors.KEY, new BlockBehaviors(blockBehaviors));
        builder.view(FurnitureBehaviors.KEY, new FurnitureBehaviors(furnitureBehaviors));
        builder.view(dev.materione.plugin.runtime.mechanics.ItemMechanics.KEY,
                new dev.materione.plugin.runtime.mechanics.ItemMechanics(itemMechanics));
        builder.view(dev.materione.core.runtime.RuntimeBlocks.KEY, runtimeBlocks.build());
        builder.view(dev.materione.core.runtime.RuntimeFurnitures.KEY, runtimeFurnitures.build());
        return builder.build();
    }

    /**
     * Turns one parsed block definition into its runtime form. The state slot
     * comes from the durable allocation table — the compiler claimed it during
     * this run's compile stage, and slots never shift (D7). Definitions whose
     * slot is missing or out of range are diagnosed and skipped.
     */
    private void compileBlock(
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
                return;
            }
            slot = Integer.parseInt(value.get());
            if (slot >= dev.materione.core.blocks.BlockStateSpaces.capacity(block.technique())) {
                diagnostics.publish(Diagnostic.warn(
                        DiagnosticCodes.BLOCK_STATE_EXHAUSTED,
                        "shadow build skipped " + block.id() + ": slot " + slot + " outside "
                                + host + " space"));
                return;
            }
        }
        runtimeBlocks.block(new dev.materione.core.runtime.RuntimeBlock(
                block.id(), block.technique(), host, slot,
                block.drops(), block.blastResistant(), block.movePolicy()));
        if (block.placedBy() != null) {
            runtimeBlocks.placedBy(block.placedBy(), block.id());
        }
        if (!block.behavior().isEmpty()) {
            dev.materione.behavior.rule.ParsedRules parsedRules =
                    dev.materione.behavior.rule.RuleParser.compile(block.id(), block.behavior());
            parsedRules.problems().forEach(diagnostics::publish);
            if (!parsedRules.isEmpty()) {
                blockBehaviors.put(block.id(), parsedRules.rules());
            }
        }
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
        String modelRef;
        if (furniture.wantsGeneratedModel()) {
            modelRef = furniture.id().namespace() + ":" + furniture.id().value() + "_model";
        } else {
            modelRef = furniture.model().contains(":")
                    ? furniture.model()
                    : furniture.id().namespace() + ":" + furniture.model();
        }
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
        if (furniture.placedBy() != null) {
            runtimeFurnitures.placedBy(furniture.placedBy(), furniture.id());
        }
        if (!furniture.behavior().isEmpty()) {
            dev.materione.behavior.rule.ParsedRules parsedRules =
                    dev.materione.behavior.rule.RuleParser.compile(furniture.id(), furniture.behavior());
            parsedRules.problems().forEach(diagnostics::publish);
            if (!parsedRules.isEmpty()) {
                furnitureBehaviors.put(furniture.id(), parsedRules.rules());
            }
        }
    }

    /**
     * Compiles one item's {@code behavior:} section into engine rules
     * (runtime-systems §2). Broken rules are diagnosed (MTR-0021/0022) and
     * skipped — their siblings still load.
     */
    private void compileBehaviors(
            ItemDefinition item,
            Map<NamespacedId, List<dev.materione.behavior.rule.Rule>> behaviors) {
        if (item.behavior().isEmpty()) {
            return;
        }
        dev.materione.behavior.rule.ParsedRules parsedRules =
                dev.materione.behavior.rule.RuleParser.compile(item.id(), item.behavior());
        parsedRules.problems().forEach(diagnostics::publish);
        if (!parsedRules.isEmpty()) {
            behaviors.put(item.id(), parsedRules.rules());
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
        RuntimeItem.Food food = null;
        for (dev.materione.plugin.runtime.mechanics.ActiveMechanic mechanic : mechanics) {
            if ("food".equals(mechanic.name())) {
                food = new RuntimeItem.Food(
                        dev.materione.plugin.runtime.mechanics.ActiveMechanic.intParam(
                                mechanic.params(), "nutrition", 1),
                        (float) dev.materione.plugin.runtime.mechanics.ActiveMechanic.doubleParam(
                                mechanic.params(), "saturation", 0),
                        dev.materione.plugin.runtime.mechanics.ActiveMechanic.boolParam(
                                mechanic.params(), "can_always_eat", false));
            }
        }
        return new RuntimeItem(
                item.id(),
                item.material(),
                modelId,
                item.displayName(),
                item.lore(),
                RuntimeItem.hashOf(item.id(), item.material(), modelId, item.displayName(), item.lore()),
                food);
    }

    /** Resolves mechanics against the frozen-at-apply registry snapshot. */
    private dev.materione.plugin.runtime.mechanics.MechanicsResolver mechanicsResolver() {
        return new dev.materione.plugin.runtime.mechanics.MechanicsResolver(
                registries.byKey(
                        dev.materione.plugin.runtime.mechanics.MechanicsResolver.REGISTRY_KEY,
                        dev.materione.api.mechanics.Mechanic.class));
    }

    private List<ContentType> contentTypes() {
        var registry = registries.registry(CONTENT_TYPES, ContentType.class);
        var out = new ArrayList<ContentType>();
        registry.entries().forEach(entry -> out.add(entry.entry()));
        return out;
    }
}
