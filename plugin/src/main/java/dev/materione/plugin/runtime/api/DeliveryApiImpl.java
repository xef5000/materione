package dev.materione.plugin.runtime.api;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.delivery.DeliveryApi;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.plugin.runtime.DeliveryService;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@link DeliveryApi} implementation over {@link DeliveryService}
 * (api.md §3.6): the candidate/published channel. Promotion is explicit and
 * hash-exact — only the candidate the author previewed can be published —
 * and per-player sends reuse the join path's published-versus-candidate
 * logic, fail-soft end to end.
 */
public final class DeliveryApiImpl implements DeliveryApi {

    private final DeliveryService delivery;

    public DeliveryApiImpl(DeliveryService delivery) {
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    @Override
    public DeliveryStatus status() {
        DeliveryService.ReleaseStatus status = delivery.releaseStatus();
        return new DeliveryStatus(
                emptyToNull(status.publishedSha256()),
                emptyToNull(status.candidateSha256()),
                emptyToNull(delivery.currentUrl()),
                delivery.isRunning());
    }

    @Override
    public ApiResult<Void> publishCandidate(ActorContext actor, String expectedCandidateSha256) {
        if (!delivery.publishCandidate(expectedCandidateSha256)) {
            return ApiResult.fail(new Diagnostic(
                    DiagnosticCodes.EDITOR_DOCUMENT_CONFLICT, Severity.WARN,
                    "the candidate changed or is no longer available",
                    null, null, "preview the latest candidate before publishing"));
        }
        return ApiResult.ok(null);
    }

    @Override
    public ApiResult<Void> sendPack(ActorContext actor, UUID player, PackTarget target) {
        Objects.requireNonNull(player, "player");
        boolean candidate = target == PackTarget.CANDIDATE;
        String reason = delivery.sendTo(player, candidate).join();
        if (reason != null) {
            return ApiResult.fail(Diagnostic.error(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED, reason));
        }
        return ApiResult.ok(null);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
