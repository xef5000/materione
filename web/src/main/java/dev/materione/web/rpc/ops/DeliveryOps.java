package dev.materione.web.rpc.ops;

import com.google.gson.JsonObject;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.delivery.DeliveryApi;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.util.function.Function;

/**
 * Candidate/published pack operations from architecture.md section 8.4,
 * served straight from {@link DeliveryApi}.
 */
public final class DeliveryOps {

    private DeliveryOps() {}

    public static void register(
            RpcDispatcher dispatcher,
            DeliveryApi delivery,
            Function<String, ActorContext> actorFor) {
        dispatcher.register("delivery.status", request -> {
            DeliveryApi.DeliveryStatus status = delivery.status();
            JsonObject payload = new JsonObject();
            payload.addProperty("publishedSha256", nullToEmpty(status.publishedSha256()));
            payload.addProperty("candidateSha256", nullToEmpty(status.candidateSha256()));
            return Wire.Response.success(request, payload);
        });
        dispatcher.register("delivery.publish", request -> {
            String expected = requiredString(request.payload(), "expectedCandidateSha256");
            var result = delivery.publishCandidate(
                    actorFor.apply(actorOf(request)), expected);
            if (!result.ok()) {
                var diagnostic = result.diagnostics().isEmpty()
                        ? null : result.diagnostics().get(0);
                return Wire.Response.failure(request, new Wire.Error(
                        diagnostic == null ? "MTR-0034" : diagnostic.code().code(),
                        diagnostic == null
                                ? "the candidate changed or is no longer available"
                                : diagnostic.message(),
                        null,
                        diagnostic == null || diagnostic.hint() == null
                                ? "preview the latest candidate before publishing"
                                : diagnostic.hint()));
            }
            return Wire.Response.success(request, new JsonObject());
        });
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String actorOf(Wire.Request request) {
        return "editor:" + request.id();
    }

    private static String requiredString(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing field '" + field + "'");
        }
        return object.get(field).getAsString();
    }
}
