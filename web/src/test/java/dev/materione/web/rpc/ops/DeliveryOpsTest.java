package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.delivery.DeliveryApi;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import org.junit.jupiter.api.Test;

class DeliveryOpsTest {

    @Test
    void publishesOnlyTheExpectedCandidateHash() {
        FakeDelivery delivery = new FakeDelivery("published", "candidate");
        RpcDispatcher dispatcher = new RpcDispatcher();
        DeliveryOps.register(dispatcher, delivery, DeliveryOpsTest::actor);

        Wire.Response stale = dispatcher.dispatch(request("delivery.publish", "wrong"));
        assertThat(stale.ok()).isFalse();
        assertThat(stale.error().code()).isEqualTo("MTR-0034");
        assertThat(delivery.published).isEqualTo("published");

        Wire.Response accepted = dispatcher.dispatch(request("delivery.publish", "candidate"));
        assertThat(accepted.ok()).isTrue();
        assertThat(delivery.published).isEqualTo("candidate");
        assertThat(delivery.candidate).isEmpty();
    }

    @Test
    void reportsPublishedAndCandidatePointers() {
        RpcDispatcher dispatcher = new RpcDispatcher();
        DeliveryOps.register(dispatcher, new FakeDelivery("one", "two"),
                DeliveryOpsTest::actor);

        JsonObject data = dispatcher.dispatch(
                new Wire.Request("delivery.status", "1", new JsonObject()))
                .data().getAsJsonObject();
        assertThat(data.get("publishedSha256").getAsString()).isEqualTo("one");
        assertThat(data.get("candidateSha256").getAsString()).isEqualTo("two");
    }

    private static Wire.Request request(String op, String hash) {
        JsonObject payload = new JsonObject();
        payload.addProperty("expectedCandidateSha256", hash);
        return new Wire.Request(op, "1", payload);
    }

    private static ActorContext actor(String attribution) {
        return new ActorContext(attribution, ActorContext.Kind.EDITOR_SESSION,
                java.util.Optional.empty(), node -> true);
    }

    private static final class FakeDelivery implements DeliveryApi {
        private String published;
        private String candidate;

        private FakeDelivery(String published, String candidate) {
            this.published = published;
            this.candidate = candidate;
        }

        @Override
        public DeliveryStatus status() {
            return new DeliveryStatus(published, candidate, null, true);
        }

        @Override
        public ApiResult<Void> publishCandidate(ActorContext actor, String expected) {
            if (candidate == null || !candidate.equals(expected)) {
                return ApiResult.fail(new dev.materione.api.diagnostics.Diagnostic(
                        dev.materione.api.diagnostics.DiagnosticCodes.EDITOR_DOCUMENT_CONFLICT,
                        dev.materione.api.diagnostics.Severity.WARN,
                        "the candidate changed or is no longer available",
                        null, null, "preview the latest candidate before publishing"));
            }
            published = candidate;
            candidate = "";
            return ApiResult.ok(null);
        }

        @Override
        public ApiResult<Void> sendPack(ActorContext actor, java.util.UUID player, PackTarget target) {
            return ApiResult.ok(null);
        }
    }
}
