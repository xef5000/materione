package dev.materione.core.pipeline;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.pipeline.graph.ContentGraph;
import dev.materione.core.pipeline.validate.ValidateStage;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs the pipeline stages in order (plugin architecture §4): parse →
 * validate → graph (allocate/compile/apply/deliver stages register with
 * later slices). Pipelines serialize — never two concurrent runs — and
 * triggers that arrive mid-run coalesce per scope (§1.2): the next run
 * picks up the union of pending scopes.
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

    private final ReentrantLock runLock = new ReentrantLock();
    private final ConcurrentLinkedQueue<ReloadScope> pendingScopes = new ConcurrentLinkedQueue<>();
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

    private volatile ContentGraph lastGraph = new ContentGraph();

    /**
     * Requests a run. If one is in flight the scope coalesces into the next
     * run instead of queueing a second concurrent pipeline (§1.2). The
     * returned result is this call's run, or null when coalesced.
     */
    public PipelineResult request(ReloadScope scope, java.nio.file.Path packsRoot) {
        Objects.requireNonNull(scope, "scope");
        if (running.get()) {
            pendingScopes.add(scope);
            return null;
        }
        runLock.lock();
        try {
            running.set(true);
            try {
                return run(scope, packsRoot);
            } finally {
                running.set(false);
            }
            // coalesced scopes: the next request() picks them up
        } finally {
            runLock.unlock();
        }
    }

    private PipelineResult run(ReloadScope scope, java.nio.file.Path packsRoot) {
        var context = new PipelineContext(scope, packsRoot, diagnostics, quarantine);
        diagnostics.publish(new Diagnostic(
                DiagnosticCodes.SCOPE_EXPANDED, Severity.INFO,
                "pipeline run started (scope " + scope + ")",
                null, null, null));
        try {
            parseStage.run(context);
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
