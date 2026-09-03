package dev.materione.web.rpc.ops;

import com.google.gson.JsonObject;
import dev.materione.web.DeliveryControl;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;

/** Candidate/published pack operations from architecture.md section 8.4. */
public final class DeliveryOps {

    private DeliveryOps() {}

    public static void register(RpcDispatcher dispatcher, DeliveryControl delivery) {
        dispatcher.register("delivery.status", request -> {
            DeliveryControl.Status status = delivery.status();
            JsonObject payload = new JsonObject();
            payload.addProperty("publishedSha256", status.publishedSha256());
            payload.addProperty("candidateSha256", status.candidateSha256());
            return Wire.Response.success(request, payload);
        });
        dispatcher.register("delivery.publish", request -> {
            String expected = requiredString(request.payload(), "expectedCandidateSha256");
            if (!delivery.publish(expected)) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0034",
                        "the candidate changed or is no longer available",
                        null,
                        "preview the latest candidate before publishing"));
            }
            return Wire.Response.success(request, new JsonObject());
        });
    }

    private static String requiredString(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing field '" + field + "'");
        }
        return object.get(field).getAsString();
    }
}
