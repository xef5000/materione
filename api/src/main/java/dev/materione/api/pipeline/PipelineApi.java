package dev.materione.api.pipeline;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Pipeline and validation control (api.md §3.5, plugin.md §4): the one
 * reload path (D6) with scoped targets, delivery modes for editor preview
 * versus default publication, and the operator's observability surfaces —
 * last apply report, pending restart-required changes, quarantine.
 */
public interface PipelineApi {

    /**
     * One pipeline run. {@link ReloadRequest#deliveryMode()} selects how pack
     * bytes are delivered afterwards: {@code PUBLISH_DEFAULT} publishes to
     * everyone, {@code CANDIDATE_PREVIEW} stages the candidate bound to the
     * actor's in-game identity only (master §8.4).
     */
    CompletableFuture<ApiResult<ReloadSummary>> reload(ActorContext actor, ReloadRequest request);

    /** The last apply report; empty before the first apply. */
    Optional<ApplyReport> lastApplyReport();

    /** Changes staged for the next boot ({@code MTR-0028/0029}). */
    List<RestartStaged> pendingRestartChanges();

    /** Currently quarantined resources and their reasons (fail-soft surface, D6). */
    List<Quarantined> quarantinedResources();

    /**
     * Reload event feed for connected editors, CLI waiters, or MCP
     * listeners ({@code push.reload}). Returns a subscription handle.
     */
    AutoCloseable subscribeReloads(Consumer<ReloadEvent> listener);

    // ------------------------------------------------------------------

    /** One reload ask: what to touch, and how to deliver resulting pack bytes. */
    record ReloadRequest(ReloadScope scope, DeliveryMode deliveryMode) {
        public static ReloadRequest everything() {
            return new ReloadRequest(new ReloadScope.Everything(), DeliveryMode.PUBLISH_DEFAULT);
        }
    }

    /** How a reload's pack bytes are delivered (master §8.4). */
    enum DeliveryMode {
        /** Changed pack bytes are published and sent to online players. */
        PUBLISH_DEFAULT,
        /** The candidate goes only to the session-opening player for preview. */
        CANDIDATE_PREVIEW
    }

    /** One restart-staged change ({@code MTR-0028/0029}). */
    record RestartStaged(NamespacedId resource, String reason) {}

    /** One quarantined resource and why. */
    record Quarantined(NamespacedId resource, Diagnostic reason) {}

    /** Reload outcome event ({@code push.reload} shape). */
    record ReloadEvent(
            boolean accepted, boolean noop, int itemsLive, int quarantined, String scope) {}
}
