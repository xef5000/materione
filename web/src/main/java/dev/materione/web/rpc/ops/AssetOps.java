package dev.materione.web.rpc.ops;

import com.google.gson.JsonObject;
import dev.materione.core.document.AssetStore;
import dev.materione.core.document.AuditLog;
import dev.materione.web.ReloadTrigger;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.util.Base64;
import java.nio.file.Path;

/**
 * The asset surface (web.md §4.2 "assets" group): list, upload, fetch and
 * delete pack-binary assets (textures, models, sounds) under
 * {@code content/packs/<pack>/assets/}. Binary rides base64-encoded INSIDE
 * the sealed channel frames — already end-to-end encrypted and signed — so
 * direct mode needs no second envelope; the §4.3 manifest+chunk scheme is a
 * RELAY-transport concern (resumability across the public store) and lands
 * with M5's cloud path. A per-asset size cap keeps WS frames bounded.
 *
 * <p>Thin RPC shell: the filesystem, caps, path rules and audit entries
 * live in {@link AssetStore} (core owns every pack mutation, D1); this
 * layer only decodes, queues the pack reload and encodes errors.
 */
public final class AssetOps {

    private AssetOps() {}

    public static void register(
            RpcDispatcher dispatcher,
            Path packsRoot,
            AuditLog audit,
            ReloadTrigger reload) {
        AssetStore store = new AssetStore(packsRoot, audit);
        dispatcher.register("assets.list", request -> {
            try {
                String pack = requiredString(request.payload(), "pack");
                JsonObject payload = new JsonObject();
                var array = new com.google.gson.JsonArray();
                for (AssetStore.AssetEntry entry : store.list(pack)) {
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
                String path = AssetStore.safeAssetPath(
                        request.payload().get("path").getAsString());
                byte[] bytes = Base64.getDecoder().decode(requiredString(request.payload(), "data"));
                int written = store.put(actorOf(request), pack, path, bytes);
                queueReload(reload, pack);
                JsonObject payload = new JsonObject();
                payload.addProperty("bytes", written);
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
                byte[] data = store.get(
                        pack, request.payload().get("path").getAsString());
                JsonObject payload = new JsonObject();
                payload.addProperty("data", Base64.getEncoder().encodeToString(data));
                return Wire.Response.success(request, payload);
            } catch (java.nio.file.NoSuchFileException e) {
                return Wire.Response.failure(request,
                        new Wire.Error("MTR-0009", "no such asset", null, null));
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("assets.move", request -> {
            JsonObject body = request.payload();
            try {
                String pack = requiredString(body, "pack");
                String from = body.get("from").getAsString();
                String to = body.get("to").getAsString();
                store.move(actorOf(request), pack, from, to);
                queueReload(reload, pack);
                return Wire.Response.success(request, new JsonObject());
            } catch (java.nio.file.NoSuchFileException e) {
                return Wire.Response.failure(request,
                        new Wire.Error("MTR-0009", "no such asset: " + e.getFile(), null, null));
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (IllegalStateException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("assets.delete", request -> {
            try {
                String pack = requiredString(request.payload(), "pack");
                boolean existed = store.delete(
                        actorOf(request), pack, request.payload().get("path").getAsString());
                if (existed) {
                    queueReload(reload, pack);
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

    private static void queueReload(ReloadTrigger reload, String pack) {
        // Whole-pack scope for asset changes: texture edits can affect any
        // referencing item, and the fingerprint cache makes no-op cheap.
        reload.reloadScopeForPreview(
                new dev.materione.api.pipeline.ReloadScope.OnePack(pack),
                RpcDispatcher.currentSessionId())
                .whenComplete((outcome, error) -> { });
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
