package dev.materione.web.rpc.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * Atomic Blockbench save-back across assets and the owning resource field
 * ({@code authoring.saveModel}). Thin RPC shell: the transaction — staging,
 * promotion, rollback, the model-links relationship entry and the audit
 * line — lives behind {@link AuthoringApi#saveModelTransaction}; this layer
 * decodes the frame and renders the exact wire errors as always.
 */
public final class AuthoringModelOps {

    private AuthoringModelOps() {}

    public static void register(
            RpcDispatcher dispatcher,
            AuthoringApi authoring,
            Function<String, ActorContext> actorFor) {
        dispatcher.register("authoring.saveModel", request -> {
            JsonObject body = request.payload();
            String pack = optional(body, "pack");
            String ownerPath = optional(body, "ownerPath");
            // Error-path target: the owner document as the inline
            // implementation knew it at throw time — for a createOwner
            // transaction that made it past the id checks, the computed path.
            String errorOwnerPath = ownerPath;
            try {
                pack = required(body, "pack");
                boolean createOwner = body.has("createOwner") && body.get("createOwner").getAsBoolean();
                boolean attachOwner = createOwner || !ownerPath.isBlank();
                int expectedRevision = attachOwner && !createOwner
                        ? body.get("rev").getAsInt() : -1;
                String modelReference = attachOwner ? required(body, "modelRef") : "";
                List<String> field = attachOwner && !createOwner
                        ? strings(body.getAsJsonArray("field")) : List.of();
                List<AuthoringApi.ModelAsset> assets = assets(body.getAsJsonArray("assets"));
                String ownerId = optional(body, "ownerId");
                String ownerType = optional(body, "ownerType");
                if (createOwner && !ownerId.isBlank()) {
                    errorOwnerPath = (ownerType.isBlank() ? "items" : ownerType)
                            + "/" + ownerId + ".yml";
                }
                ApiResult<AuthoringApi.SaveModelResult> result = authoring.saveModelTransaction(
                        actorFor.apply("editor:" + RpcDispatcher.currentSessionId()),
                        new AuthoringApi.SaveModelRequest(
                                pack, ownerPath, createOwner, ownerId, ownerType,
                                optional(body, "ownerName"), modelReference, field,
                                expectedRevision, optional(body, "sourcePath"), assets));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), pack, ownerPath, errorOwnerPath);
                }
                AuthoringApi.SaveModelResult updated = result.value();
                JsonObject payload = new JsonObject();
                if (updated.revision() != null) {
                    payload.addProperty("rev", updated.revision());
                    payload.addProperty("raw", updated.raw());
                    payload.addProperty("ownerPath", updated.ownerPath());
                }
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0035", e.getMessage(), pack + "/" + errorOwnerPath, null));
            } catch (Exception e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0011", "model transaction failed: " + e.getMessage(),
                        pack + "/" + errorOwnerPath, "no partial model changes were kept"));
            }
        });
    }

    private static Wire.Response error(
            Wire.Request request, List<dev.materione.api.diagnostics.Diagnostic> diagnostics,
            String pack, String ownerPath, String errorOwnerPath) {
        dev.materione.api.diagnostics.Diagnostic diagnostic =
                diagnostics.isEmpty() ? null : diagnostics.get(0);
        String code = diagnostic == null ? "MTR-0011" : diagnostic.code().code();
        if ("MTR-0034".equals(code)) {
            return Wire.Response.failure(request, new Wire.Error(
                    code, diagnostic.message(), pack + "/" + ownerPath,
                    "return to the item and reopen its model"));
        }
        if ("MTR-0035".equals(code)) {
            return Wire.Response.failure(request, new Wire.Error(
                    code, diagnostic.message(), pack + "/" + errorOwnerPath, null));
        }
        return Wire.Response.failure(request, new Wire.Error(
                code, diagnostic == null ? "request failed" : diagnostic.message(),
                pack + "/" + errorOwnerPath,
                diagnostic == null ? null : diagnostic.hint()));
    }

    private static List<AuthoringApi.ModelAsset> assets(JsonArray values) {
        if (values == null || values.size() > 128) {
            throw new IllegalArgumentException("model transaction has too many assets");
        }
        List<AuthoringApi.ModelAsset> assets = new ArrayList<>();
        for (var value : values) {
            JsonObject entry = value.getAsJsonObject();
            String relative = required(entry, "path").replace('\\', '/');
            byte[] bytes = Base64.getDecoder().decode(required(entry, "data"));
            assets.add(new AuthoringApi.ModelAsset(relative, bytes));
        }
        return assets;
    }

    private static List<String> strings(JsonArray values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("model owner field is required");
        }
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsString()));
        return result;
    }

    private static String required(JsonObject body, String field) {
        if (!body.has(field) || !body.get(field).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing field '" + field + "'");
        }
        return body.get(field).getAsString();
    }

    private static String optional(JsonObject body, String field) {
        return body.has(field) && body.get(field).isJsonPrimitive()
                ? body.get(field).getAsString() : "";
    }
}
