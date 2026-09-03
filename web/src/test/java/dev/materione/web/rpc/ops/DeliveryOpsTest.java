package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import dev.materione.web.DeliveryControl;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import org.junit.jupiter.api.Test;

class DeliveryOpsTest {

    @Test
    void publishesOnlyTheExpectedCandidateHash() {
        FakeDelivery delivery = new FakeDelivery("published", "candidate");
        RpcDispatcher dispatcher = new RpcDispatcher();
        DeliveryOps.register(dispatcher, delivery);

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
        DeliveryOps.register(dispatcher, new FakeDelivery("one", "two"));

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

    private static final class FakeDelivery implements DeliveryControl {
        private String published;
        private String candidate;

        private FakeDelivery(String published, String candidate) {
            this.published = published;
            this.candidate = candidate;
        }

        @Override
        public Status status() {
            return new Status(published, candidate);
        }

        @Override
        public boolean publish(String expectedCandidateSha256) {
            if (!candidate.equals(expectedCandidateSha256)) {
                return false;
            }
            published = candidate;
            candidate = "";
            return true;
        }
    }
}
