package dev.materione.core.pipeline;

import dev.materione.api.pipeline.ReloadScope;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.pipeline.graph.ContentGraph;
import dev.materione.core.pipeline.validate.ValidateStage;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs the pipeline stages in order (plugin architecture §4): parse →
 * validate → graph (allocate/compile/apply/deliver stages register with
 * later slices). Pipelines serialize — never two concurrent runs — and
 * the composed pipeline API owns scope coalescing across the complete
 * parse → compile → apply → deliver lifecycle (§1.2).
 *
 * <p>Fail-soft (D6, §8): a stage that throws aborts the run cleanly with
 * MTR-0011 — live state is untouched, because nothing half-built is ever
 * handed onward.
 */
public final class PipelineRunner {

    private final DiagnosticsBus diagnostics;
    private final QuarantineTracker quarantine;
    private final ParseStage parseStage = new ParseStage();
    private final ValidateStage validateStage = new ValidateStage();
    private final GraphStage graphStage = new GraphStage();
    private volatile FingerprintCache fingerprints = FingerprintCache.empty();
    private volatile BuiltinPacks builtinPacks = BuiltinPacks.none();

    private final ReentrantLock runLock = new ReentrantLock();
    private final AtomicBoolean running = new AtomicBoolean();

    public PipelineRunner(DiagnosticsBus diagnostics, QuarantineTracker quarantine) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
    }

    /** True while a run is in flight (tests and doctor may look, never block on it). */
    public boolean isRunning() {
        return running.get();
    }

    /** The graph of the last completed run (empty before the first run). */
    public ContentGraph lastGraph() {
        return lastGraph;
    }

    /** The packs parsed by the last run (empty before the first run). */
    public java.util.List<ContentPack> lastPacks() {
        return lastPacks;
    }

    /**
     * Sets the packs the plugin ships in its jar (master §7.3); every later run parses them beside the packs on
     * disk. Set once at boot, before the first run.
     */
    public void builtinPacks(BuiltinPacks packs) {
        this.builtinPacks = packs == null ? BuiltinPacks.none() : packs;
    }

    /** The built-in packs runs parse. */
    public BuiltinPacks builtinPacks() {
        return builtinPacks;
    }

    /** Replaces the fingerprint table (boot-time load from {@code fingerprints.bin}). */
    public void replaceFingerprints(FingerprintCache cache) {
        this.fingerprints = cache == null ? FingerprintCache.empty() : cache;
    }

    /** Persists the fingerprint table; failures are non-fatal (the next run re-hashes). */
    public boolean saveFingerprints(java.nio.file.Path file) {
        return fingerprints.save(file);
    }

    private volatile ContentGraph lastGraph = new ContentGraph();
    private volatile java.util.List<ContentPack> lastPacks = java.util.List.of();
    private volatile ParsedContent lastParsed = ParsedContent.EMPTY;

    /** The packs, asset index and painting sizes of the last run (empty before the first run). */
    public ParsedContent lastParsed() {
        return lastParsed;
    }

    /**
     * The run's packs with their asset index. A targeted run indexes only the packs it rebuilt, so the assets of a
     * pack it rode over (the same instance as in the previous run) are carried from the previous parse.
     */
    private ParsedContent parsedOf(PipelineContext context, java.util.List<ContentPack> previous) {
        var assets = new java.util.LinkedHashSet<>(context.knownAssets());
        var sizes = new java.util.LinkedHashMap<>(context.paintingSizes());
        var ridden = new java.util.HashSet<String>();
        for (ContentPack pack : context.packs()) {
            if (previous.stream().anyMatch(old -> old == pack)) {
                ridden.add(pack.id());
            }
        }
        if (!ridden.isEmpty()) {
            ParsedContent before = lastParsed;
            before.knownAssets().stream().filter(id -> ridden.contains(id.namespace())).forEach(assets::add);
            before.paintingSizes().forEach((id, size) -> {
                if (ridden.contains(id.namespace())) {
                    sizes.put(id, size);
                }
            });
        }
        return new ParsedContent(context.packs(), assets, sizes);
    }

    /**
     * Requests a run, serializing the core stages without dropping requests.
     * Full-lifecycle coalescing belongs to the composed pipeline API.
     */
    public PipelineResult request(ReloadScope scope, java.nio.file.Path packsRoot) {
        Objects.requireNonNull(scope, "scope");
        runLock.lock();
        try {
            running.set(true);
            try {
                return run(scope, packsRoot);
            } finally {
                running.set(false);
            }
        } finally {
            runLock.unlock();
        }
    }

    private PipelineResult run(ReloadScope scope, java.nio.file.Path packsRoot) {
        var context = new PipelineContext(scope, packsRoot, diagnostics, quarantine);
        context.previousPacks(lastPacks);
        context.fingerprints(fingerprints);
        context.builtinPacks(builtinPacks);
        diagnostics.publish(new Diagnostic(
                DiagnosticCodes.SCOPE_EXPANDED, Severity.INFO,
                "pipeline run started (scope " + scope + ")",
                null, null, null));
        try {
            quarantine.clear();
            parseStage.run(context);
            lastParsed = parsedOf(context, lastPacks);
            lastPacks = java.util.List.copyOf(context.packs());
            validateStage.run(context);
            graphStage.run(context);
            lastGraph = context.graph();
        } catch (RuntimeException e) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                    "pipeline aborted: " + e,
                    null, null, "this is a Materione bug — please report it; the live state is untouched"));
            return PipelineResult.aborted(context.packs().size(), 0);
        }
        int valid = context.graph().nodeCount();
        return new PipelineResult(
                context.packs().size(),
                context.packs().stream().mapToInt(p -> p.resources().size()).sum(),
                valid,
                quarantine.size(),
                context.graph().edges().size(),
                true);
    }
}
