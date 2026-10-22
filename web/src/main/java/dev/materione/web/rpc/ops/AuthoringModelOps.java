package dev.materione.web.rpc.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentConflictException;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransaction;
import dev.materione.core.document.ModelTransactionService;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.web.ReloadTrigger;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Atomic Blockbench save-back across assets and the owning resource field
 * ({@code authoring.saveModel}). Thin RPC shell: the transaction — staging,
 * promotion, rollback, the model-links relationship entry and the audit
 * line — lives in {@link ModelTransactionService} (core owns every pack
 * mutation, D1); this layer decodes the frame, queues the pack reload and
 * renders the exact wire errors as always.
 */
public final class AuthoringModelOps {

    private AuthoringModelOps() {}

    public static void register(
            RpcDispatcher dispatcher,
            DocumentStore documents,
            Path packsRoot,
            AuditLog audit,
            ReloadTrigger reload) {
        register(dispatcher, documents, packsRoot, audit, reload, () -> {});
    }

    static void register(
            RpcDispatcher dispatcher,
            DocumentStore documents,
            Path packsRoot,
            AuditLog audit,
            ReloadTrigger reload,
            Runnable afterAssetPromotion) {
        ModelTransactionService transactions =
                new ModelTransactionService(documents, packsRoot, audit);
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
                List<ModelTransaction.Asset> assets = assets(body.getAsJsonArray("assets"));
                String sourcePath = optional(body, "sourcePath");
                String ownerId = optional(body, "ownerId");
                String ownerType = optional(body, "ownerType");
                if (createOwner && !ownerId.isBlank()) {
                    errorOwnerPath = (ownerType.isBlank() ? "items" : ownerType)
                            + "/" + ownerId + ".yml";
                }
                ModelTransaction spec = new ModelTransaction(
                        pack, ownerPath, createOwner, ownerId, ownerType,
                        optional(body, "ownerName"), modelReference, field,
                        expectedRevision, sourcePath, assets,
                        "editor:" + RpcDispatcher.currentSessionId());
                DocumentStore.Document updated = transactions.save(spec, afterAssetPromotion);
                reload.reloadScopeForPreview(
                        new ReloadScope.OnePack(pack),
                        RpcDispatcher.currentSessionId());
                JsonObject payload = new JsonObject();
                if (updated != null) {
                    payload.addProperty("rev", updated.revision());
                    payload.addProperty("raw", updated.raw());
                    payload.addProperty("ownerPath", updated.path());
                }
                return Wire.Response.success(request, payload);
            } catch (DocumentConflictException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0034", e.getMessage(), pack + "/" + ownerPath,
                        "return to the item and reopen its model"));
            } catch (IllegalArgumentException | IllegalStateException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0035", e.getMessage(), pack + "/" + errorOwnerPath, null));
            } catch (Exception e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0011", "model transaction failed: " + e.getMessage(),
                        pack + "/" + errorOwnerPath, "no partial model changes were kept"));
            }
        });
    }

    private static List<ModelTransaction.Asset> assets(JsonArray values) {
        if (values == null || values.size() > 128) {
            throw new IllegalArgumentException("model transaction has too many assets");
        }
        List<ModelTransaction.Asset> assets = new ArrayList<>();
        for (var value : values) {
            JsonObject entry = value.getAsJsonObject();
            String relative = required(entry, "path").replace('\\', '/');
            byte[] bytes = Base64.getDecoder().decode(required(entry, "data"));
            assets.add(new ModelTransaction.Asset(relative, bytes));
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
