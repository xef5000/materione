package dev.materione.plugin.runtime.api;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.pipeline.ApplyReport;
import dev.materione.api.pipeline.PipelineApi;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.api.pipeline.ReloadSummary;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.storage.RestartStaging;
import dev.materione.plugin.runtime.DeliveryService;
import dev.materione.plugin.runtime.PipelineService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;

/**
 * The {@link PipelineApi} implementation — the ONE reload path (D6), ported
 * from the editor transport's internal trigger so commands, the editor and
 * future MCP callers share identical semantics: the run executes off the
 * main thread through the scheduling gateway, the world datapack applies on
 * every path (previews included — its restart-required ledger stays honest),
 * and the compiled pack bytes are delivered according to the request's
 * delivery mode: {@code PUBLISH_DEFAULT} publishes to everyone,
 * {@code CANDIDATE_PREVIEW} stages the candidate bound to the actor's
 * in-game identity only (master §8.4).
 *
 * <p>Fail-soft end to end: the returned future always completes — with the
 * {@link ReloadSummary}, or a structured diagnostic when the run was
 * skipped (another run in flight) or threw.
 */
public final class PipelineApiImpl implements PipelineApi {

    private final Plugin plugin;
    private final SchedulingGateway scheduling;
    private final PipelineService pipeline;
    private final QuarantineTracker quarantine;
    private final RestartStaging restartStaging;
    private final DeliveryService delivery;
    private final Path packsRoot;
    private final Path generatedDir;
    private final Path storageDir;
    private final Consumer<ReloadSummary> datapackApplier;
    private final List<Consumer<ReloadEvent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * @param datapackApplier applies the staged world datapack after each
     *                        run (the plugin's DatapackService); nullable when
     *                        no datapack service is wired
     */
    public PipelineApiImpl(
            Plugin plugin,
            SchedulingGateway scheduling,
            PipelineService pipeline,
            QuarantineTracker quarantine,
            RestartStaging restartStaging,
            DeliveryService delivery,
            Path packsRoot,
            Path generatedDir,
            Path storageDir,
            Consumer<ReloadSummary> datapackApplier) {
        this.plugin = plugin;
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
        this.restartStaging = Objects.requireNonNull(restartStaging, "restartStaging");
        this.delivery = delivery;
        this.packsRoot = Objects.requireNonNull(packsRoot, "packsRoot");
        this.generatedDir = Objects.requireNonNull(generatedDir, "generatedDir");
        this.storageDir = Objects.requireNonNull(storageDir, "storageDir");
        this.datapackApplier = datapackApplier;
    }

    @Override
    public CompletableFuture<ApiResult<ReloadSummary>> reload(ActorContext actor, PipelineApi.ReloadRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<ApiResult<ReloadSummary>> future = new CompletableFuture<>();
        scheduling.executeAsync(plugin, () -> {
            ApiResult<ReloadSummary> result = run(actor, request);
            future.complete(result);
            ReloadSummary summary = result.value();
            ReloadEvent event = result.ok() && summary != null
                    ? new ReloadEvent(true, summary.noop(), summary.itemsLive(),
                            summary.quarantined(), scopeLabel(request.scope()))
                    : new ReloadEvent(false, true, -1, -1, scopeLabel(request.scope()));
            for (Consumer<ReloadEvent> listener : listeners) {
                try {
                    listener.accept(event);
                } catch (RuntimeException e) {
                    // one bad subscriber never blocks the feed (D6)
                }
            }
        });
        return future;
    }

    /** One synchronous run + delivery binding; never throws. */
    private ApiResult<ReloadSummary> run(ActorContext actor, PipelineApi.ReloadRequest request) {
        ReloadSummary summary;
        try {
            summary = pipeline.reload(request.scope(), packsRoot, generatedDir, storageDir);
            // the world datapack applies on every reload path, previews
            // included — its restart-required ledger stays honest either way
            if (datapackApplier != null) {
                datapackApplier.accept(summary);
            }
            if (!summary.skipped() && summary.packSha256() != null
                    && summary.packZip() != null && delivery != null) {
                if (request.deliveryMode() == DeliveryMode.CANDIDATE_PREVIEW) {
                    delivery.stageCandidate(
                            summary.packZip(),
                            summary.packSha256(),
                            actor == null ? null : actor.playerUuid().orElse(null));
                } else {
                    delivery.publish(summary.packZip(), summary.packSha256());
                }
            }
        } catch (Exception e) {
            // fail-soft: a broken reload never breaks the caller's reply
            return ApiResult.fail(Diagnostic.error(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "reload failed: " + e.getMessage()));
        }
        if (summary.skipped()) {
            // the same diagnostic PipelineService publishes for the skip
            return ApiResult.fail(Diagnostic.warn(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "reload skipped: another run is in flight or the run aborted"));
        }
        return ApiResult.ok(summary);
    }

    @Override
    public Optional<ApplyReport> lastApplyReport() {
        return Optional.ofNullable(pipeline.lastReport());
    }

    @Override
    public List<RestartStaged> pendingRestartChanges() {
        List<RestartStaged> out = new ArrayList<>();
        for (RestartStaging.Entry entry : restartStaging.load()) {
            out.add(new RestartStaged(entry.resource(), entry.reason()));
        }
        return out;
    }

    @Override
    public List<Quarantined> quarantinedResources() {
        List<Quarantined> out = new ArrayList<>();
        for (QuarantineTracker.Quarantined entry : quarantine.all()) {
            out.add(new Quarantined(entry.resource(), entry.reason()));
        }
        return out;
    }

    @Override
    public AutoCloseable subscribeReloads(Consumer<ReloadEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Human-readable scope for event payloads ({@code push.reload} shape). */
    private static String scopeLabel(ReloadScope scope) {
        if (scope instanceof ReloadScope.OnePack pack) {
            return "one-pack:" + pack.packId();
        }
        if (scope instanceof ReloadScope.FileSet files) {
            return "files:" + files.files().size();
        }
        return "everything";
    }
}
