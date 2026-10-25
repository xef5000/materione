package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.content.items.ItemDefinition;
import dev.materione.content.items.ItemType;
import dev.materione.content.recipes.RecipeType;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.PipelineResult;
import dev.materione.core.pipeline.PipelineRunner;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.core.runtime.ContentRuntime;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.core.runtime.RuntimeRecipe;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.pack.allocation.AllocationTable;
import dev.materione.pack.compile.PackCompiler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Performance budgets, CI-tracked from M1 (plugin.md §9): regressions block
 * merge, they do not "warn". Measured against the fixture corpus (sample +
 * the 50-item corpus pack) with the real stage classes — the same
 * composition the harness exercises live on Paper.
 *
 * <p>Deferred budgets (documented, not yet owned): cold item detect
 * ~10–50 µs needs a live PDC read (harness-level from M4, when players
 * exist in scenarios).
 */
class ContentPerfBudgetTest {

    private static final long FULL_RELOAD_BUDGET_MS = 10_000;
    private static final long PACK_BUILD_BUDGET_MS = 30_000;
    private static final long NO_OP_DECISION_BUDGET_MS = 100;
    private static final long APPLY_SWAP_BUDGET_MS = 50;
    private static final long TARGETED_RELOAD_BUDGET_MS = 1_000;
    private static final long RESOLVE_BUDGET_NS = 1_000;

    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(256);
    private final QuarantineTracker quarantine = new QuarantineTracker();

    @Test
    void fullReloadOfCorpusUnderTenSeconds(@TempDir Path workDir) {
        long start = System.nanoTime();
        Loaded loaded = loadCorpus(workDir);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(loaded.shadow.itemCount()).isEqualTo(62);
        assertThat(elapsedMs)
                .as("full reload (parse + validate + graph + compile + shadow build) wall time")
                .isLessThan(FULL_RELOAD_BUDGET_MS);
    }

    @Test
    void packBuildUnderThirtySeconds(@TempDir Path workDir) {
        var runner = new PipelineRunner(diagnostics, quarantine);
        runner.request(new ReloadScope.Everything(), fixturesPacks());

        long start = System.nanoTime();
        var outcome = new PackCompiler().compile(
                runner.lastPacks(), List.of(new ItemType(), new RecipeType()),
                AllocationTable.load(workDir.resolve("storage/allocation.bin")),
                workDir.resolve("generated"), diagnostics, quarantine, -1);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(outcome).isNotNull();
        // world-pack items are material-only (no modelRef) → no definition JSON
        assertThat(outcome.itemDefinitions()).isEqualTo(34);
        assertThat(elapsedMs).as("pack build of the corpus").isLessThan(PACK_BUILD_BUDGET_MS);
    }

    @Test
    void noOpReloadDecisionUnder100Milliseconds(@TempDir Path workDir) {
        Loaded first = loadCorpus(workDir);

        // nothing changed: a second compile must produce identical bytes (D7)
        // and the no-op decision must cost less than the budget
        var runner = new PipelineRunner(diagnostics, quarantine);
        runner.request(new ReloadScope.Everything(), fixturesPacks());
        long start = System.nanoTime();
        var second = new PackCompiler().compile(
                runner.lastPacks(), List.of(new ItemType(), new RecipeType()),
                first.allocation(), workDir.resolve("generated"), diagnostics, quarantine, -1);
        boolean noOp = second.sha256().contentEquals(first.outcome().sha256());
        long elapsedNanos = System.nanoTime() - start;
        long elapsedMs = Duration.ofNanos(elapsedNanos).toMillis();

        System.out.println("MATERIONE_NOOP_PERF wallNanos=" + elapsedNanos
                + " wallMillis=" + elapsedMs
                + " budgetMillis=" + NO_OP_DECISION_BUDGET_MS
                + " shaEqual=" + noOp);
        assertThat(packMetadata(second.stagedZip())).doesNotContain("\"overlays\"");
        assertThat(noOp).as("deterministic compile: unchanged corpus → identical sha").isTrue();
        assertThat(elapsedMs)
                .as("no-op decision (recompile + sha comparison; M2 adds fingerprint skipping)")
                .isLessThan(NO_OP_DECISION_BUDGET_MS);
    }

    private static String packMetadata(Path zipPath) {
        try (var zip = new ZipFile(zipPath.toFile())) {
            var entry = zip.getEntry("pack.mcmeta");
            assertThat(entry).isNotNull();
            try (var in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void itemResolveSteadyStateUnderOneMicrosecond(@TempDir Path workDir) {
        RuntimeRegistries registries = loadCorpus(workDir).shadow;
        NamespacedId gem = NamespacedId.parse("corpus:gem_red");

        // Best of up to three independent measurement attempts: noise (GC,
        // background JIT, hypervisor steal) only ever inflates rounds, so the
        // minimum estimates the steady-state lookup cost — and a real
        // regression raises every round of every attempt, keeping plugin.md
        // §9 semantics. The within-pass minimum alone (#44) still failed when
        // a shared runner stayed degraded across the whole ~2s window (#48).
        int rounds = 11;
        int lookupsPerRound = 20_000;
        long bestNs = Long.MAX_VALUE;
        long[] bestMeans = null;
        for (int attempt = 0; attempt < 3 && bestNs >= RESOLVE_BUDGET_NS; attempt++) {
            for (int i = 0; i < 100_000; i++) { // warmup: enough work for C2 to settle
                registries.item(gem);
            }
            long[] means = new long[rounds];
            for (int round = 0; round < rounds; round++) {
                long start = System.nanoTime();
                for (int i = 0; i < lookupsPerRound; i++) {
                    registries.item(gem);
                }
                means[round] = (System.nanoTime() - start) / lookupsPerRound;
            }
            Arrays.sort(means);
            if (means[0] < bestNs) {
                bestNs = means[0];
                bestMeans = means;
            }
        }

        assertThat(registries.item(gem)).isPresent();
        assertThat(bestNs)
                .as("registry-map resolve best round of up to 3 attempts x %d rounds x %d lookups,"
                                + " best attempt's round means (ns) %s",
                        rounds, lookupsPerRound, Arrays.toString(bestMeans))
                .isLessThan(RESOLVE_BUDGET_NS);
    }

    @Test
    void applySwapUnder50Milliseconds(@TempDir Path workDir) {
        Loaded loaded = loadCorpus(workDir);
        var runtime = new ContentRuntime();

        long start = System.nanoTime();
        var report = runtime.apply(loaded.shadow, loaded.outcome().sha256());
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(report.added()).isEqualTo(62);
        assertThat(elapsedMs)
                .as("apply swap of 58 items — the main-thread stall proxy (0 stalls > 50 ms)")
                .isLessThan(APPLY_SWAP_BUDGET_MS);
    }

    @Test
    void targetedSingleFileReloadUnderOneSecond(@TempDir Path workDir) throws Exception {
        Loaded loaded = loadCorpus(workDir);
        var runner = new PipelineRunner(diagnostics, quarantine);
        runner.request(new ReloadScope.Everything(), fixturesPacks());
        Path target = fixturesPacks().resolve("corpus").resolve("items").resolve("amulet_red.yml");
        assertThat(java.nio.file.Files.exists(target)).isTrue();

        long start = System.nanoTime();
        var runtime = new ContentRuntime();
        PipelineResult result = runner.request(
                new ReloadScope.FileSet(java.util.Set.of(target)), fixturesPacks());
        RuntimeRegistries shadow = buildShadow(runner.lastPacks());
        runtime.diffAgainst(shadow, loaded.outcome().sha256());
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(result.completed()).isTrue();
        assertThat(shadow.itemCount()).isEqualTo(62);
        assertThat(elapsedMs)
                .as("targeted single-file reload (scoped parse + compile + shadow + pre-apply diff)")
                .isLessThan(TARGETED_RELOAD_BUDGET_MS);
    }

    // --- composition --------------------------------------------------------

    private record Loaded(PackCompiler.Outcome outcome, AllocationTable allocation,
            RuntimeRegistries shadow) {}

    /** One full composition run over the fixture corpus (both packs). */
    private Loaded loadCorpus(Path workDir) {
        var runner = new PipelineRunner(diagnostics, quarantine);
        var result = runner.request(new ReloadScope.Everything(), fixturesPacks());
        assertThat(result.completed()).isTrue();

        var allocation = AllocationTable.load(workDir.resolve("storage/allocation.bin"));
        var outcome = new PackCompiler().compile(
                runner.lastPacks(), List.of(new ItemType(), new RecipeType()),
                allocation, workDir.resolve("generated"), diagnostics, quarantine, -1);
        assertThat(outcome).isNotNull();
        return new Loaded(outcome, allocation, buildShadow(runner.lastPacks()));
    }

    /** The PipelineService.buildShadow composition (pure — no Bukkit pieces). */
    private static RuntimeRegistries buildShadow(List<ContentPack> packs) {
        var itemType = new ItemType();
        var recipeType = new RecipeType();
        var builder = RuntimeRegistries.builder();

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
                    Optional<Object> parsed = itemType.parse(resource.id(), raw, templates);
                    if (parsed.isPresent() && parsed.get() instanceof ItemDefinition item) {
                        builder.item(toRuntimeItem(item));
                    }
                } else if ("recipe".equals(resource.contentType())) {
                    Optional<Object> parsed = recipeType.parse(resource.id(), raw, templates);
                    if (parsed.isPresent() && parsed.get() instanceof RecipeType.Recipe recipe) {
                        builder.recipe(new RuntimeRecipe(
                                recipe.id(), recipe.kind(), recipe.result(), recipe.shape(),
                                recipe.shapedIngredients(), recipe.ingredients(),
                                recipe.experience(), recipe.cookingTime()));
                    }
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
                item.id(), item.material(), modelId, item.displayName(), item.lore(),
                RuntimeItem.hashOf(item.id(), item.material(), modelId, item.displayName(), item.lore()));
    }

    private static Path fixturesPacks() {
        return Path.of(System.getProperty("materione.fixtures.root")).resolve("packs");
    }
}
