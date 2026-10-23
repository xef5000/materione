package dev.materione.web.rpc.ops;

import com.google.gson.JsonObject;
import dev.materione.api.ApiResult;
import dev.materione.api.assets.AssetApi;
import dev.materione.api.auth.ActorContext;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.util.Base64;
import java.util.function.Function;

/**
 * The asset surface (web.md §4.2 "assets" group): list, upload, fetch and
 * delete pack-binary assets (textures, models, sounds) under
 * {@code content/packs/<pack>/assets/}. Binary rides base64-encoded INSIDE
 * the sealed channel frames — already end-to-end encrypted and signed — so
 * direct mode needs no second envelope; the §4.3 manifest+chunk scheme is a
 * RELAY-transport concern and lands with M5's cloud path.
 *
 * <p>Thin RPC shell: the filesystem, caps, path rules, audit entries and the
 * pack-wide preview reload live behind {@link AssetApi}; this layer only
 * decodes frames and renders the wire errors.
 */
public final class AssetOps {

    private AssetOps() {}

    public static void register(
            RpcDispatcher dispatcher,
            AssetApi assets,
            Function<String, ActorContext> actorFor) {
        dispatcher.register("assets.list", request -> {
            try {
                String pack = requiredString(request.payload(), "pack");
                JsonObject payload = new JsonObject();
                var array = new com.google.gson.JsonArray();
                for (AssetApi.AssetInfo entry : assets.listAssets(pack, null).value()) {
                    JsonObject object = new JsonObject();
                    object.addProperty("path", entry.path());
                    object.addProperty("bytes", entry.sizeBytes());
                    object.addProperty("kind", entry.kind());
                    array.add(object);
                }
                payload.add("assets", array);
                return Wire.Response.success(request, payload);
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("assets.put", request -> {
            try {
                String pack = requiredString(request.payload(), "pack");
                String path = request.payload().get("path").getAsString();
                byte[] bytes = Base64.getDecoder().decode(requiredString(request.payload(), "data"));
                ApiResult<Integer> result = assets.putAsset(
                        actorFor.apply(actorOf(request)), pack, path, bytes);
                if (!result.ok()) {
                    return error(request, result.diagnostics());
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("bytes", result.value());
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("assets.get", request -> {
            try {
                String pack = requiredString(request.payload(), "pack");
                ApiResult<byte[]> result = assets.getAsset(
                        pack, request.payload().get("path").getAsString());
                if (!result.ok()) {
                    return error(request, result.diagnostics());
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("data", Base64.getEncoder().encodeToString(result.value()));
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("assets.move", request -> {
            JsonObject body = request.payload();
            try {
                ApiResult<Void> result = assets.moveAsset(
                        actorFor.apply(actorOf(request)),
                        requiredString(body, "pack"),
                        body.get("from").getAsString(),
                        body.get("to").getAsString());
                if (!result.ok()) {
                    return error(request, result.diagnostics());
                }
                return Wire.Response.success(request, new JsonObject());
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("assets.delete", request -> {
            try {
                ApiResult<Void> result = assets.deleteAsset(
                        actorFor.apply(actorOf(request)),
                        requiredString(request.payload(), "pack"),
                        request.payload().get("path").getAsString());
                if (!result.ok()) {
                    return error(request, result.diagnostics());
                }
                return Wire.Response.success(request, new JsonObject());
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });
    }

    // ------------------------------------------------------------------

    private static Wire.Response error(Wire.Request request, java.util.List<dev.materione.api.diagnostics.Diagnostic> diagnostics) {
        dev.materione.api.diagnostics.Diagnostic diagnostic =
                diagnostics.isEmpty() ? null : diagnostics.get(0);
        return Wire.Response.failure(request, new Wire.Error(
                diagnostic == null ? "MTR-0011" : diagnostic.code().code(),
                diagnostic == null ? "request failed" : diagnostic.message(),
                null,
                diagnostic == null ? null : diagnostic.hint()));
    }

    private static Wire.Response failure(Wire.Request request, Exception e) {
        return Wire.Response.failure(request,
                new Wire.Error("MTR-0011", String.valueOf(e.getMessage()), null, null));
    }

    private static Wire.Response badRequest(Wire.Request request, String message) {
        return Wire.Response.failure(request, new Wire.Error("MTR-0008", message, null, null));
    }

    private static String actorOf(Wire.Request request) {
        return "editor:" + request.id();
    }

    private static String requiredString(JsonObject object, String field) {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing field '" + field + "'");
        }
        return value.getAsString();
    }
}
