package dev.materione.web.rpc.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentConflictException;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.pipeline.ReloadScope;
import dev.materione.web.ReloadTrigger;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Atomic Blockbench save-back across assets and the owning resource field. */
public final class AuthoringModelOps {

    private static final int MAX_TRANSACTION_BYTES = 8 * 1024 * 1024;
    private static final Object MODEL_SAVE_LOCK = new Object();

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
        dispatcher.register("authoring.saveModel", request -> {
            JsonObject body = request.payload();
            String pack = optional(body, "pack");
            String ownerPath = optional(body, "ownerPath");
            try {
                synchronized (MODEL_SAVE_LOCK) {
                pack = required(body, "pack");
                if (!pack.matches("[a-z0-9_-]{2,32}")) {
                    throw new IllegalArgumentException("invalid pack id: " + pack);
                }
                Path normalizedPacksRoot = packsRoot.normalize();
                Path packRoot = normalizedPacksRoot.resolve(pack).normalize();
                if (!packRoot.startsWith(normalizedPacksRoot)) {
                    throw new IllegalArgumentException("pack escapes content root");
                }
                if (!Files.isRegularFile(packRoot.resolve("pack.yml"))) {
                    throw new IllegalArgumentException("pack does not exist: " + pack);
                }
                boolean createOwner = body.has("createOwner") && body.get("createOwner").getAsBoolean();
                boolean attachOwner = createOwner || !ownerPath.isBlank();
                int expectedRevision = attachOwner && !createOwner
                        ? body.get("rev").getAsInt() : -1;
                String modelReference = attachOwner ? required(body, "modelRef") : "";
                if (attachOwner && !modelReference.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                    throw new IllegalArgumentException("invalid model reference: " + modelReference);
                }
                List<String> field = attachOwner && !createOwner
                        ? strings(body.getAsJsonArray("field")) : List.of();
                List<StagedAsset> assets = assets(pack, packsRoot, body.getAsJsonArray("assets"));
                if (createOwner) {
                    String ownerId = required(body, "ownerId");
                    // Blocks and furniture own models too; items stay the
                    // default so older clients keep working.
                    String ownerType = optional(body, "ownerType");
                    if (ownerType.isBlank()) {
                        ownerType = "items";
                    }
                    if (!Scaffolds.SCAFFOLD_TYPES.contains(ownerType)) {
                        throw new IllegalArgumentException("unknown owner type: " + ownerType);
                    }
                    ownerPath = ownerType + "/" + ownerId + ".yml";
                    if (Files.exists(packsRoot.resolve(pack).resolve(ownerPath))) {
                        throw new IllegalStateException("document already exists: " + ownerPath);
                    }
                } else if (attachOwner) {
                    DocumentStore.Document owner = documents.read(pack, ownerPath);
                    if (owner.revision() != expectedRevision) {
                        throw new DocumentConflictException(
                                "expected revision " + expectedRevision
                                        + " but current revision is " + owner.revision(),
                                expectedRevision,
                                owner.revision());
                    }
                }
                if (attachOwner) {
                    String sourcePath = optional(body, "sourcePath");
                    if (!sourcePath.isBlank()) {
                        Path expectedSource = packsRoot.resolve(pack).resolve(sourcePath).normalize();
                        boolean included = assets.stream()
                                .anyMatch(asset -> asset.target().equals(expectedSource));
                        if (!sourcePath.endsWith(".bbmodel") || !included) {
                            throw new IllegalArgumentException(
                                    "model source must name a .bbmodel in this transaction");
                        }
                    }
                    String linkField = createOwner
                            ? required(body, "ownerId") + ".model"
                            : String.join(".", field);
                    assets = new ArrayList<>(assets);
                    assets.add(relationship(
                            packRoot, ownerPath, linkField,
                            modelReference, sourcePath));
                }

                Map<Path, Backup> backups = new LinkedHashMap<>();
                DocumentStore.Checkpoint ownerCheckpoint =
                        attachOwner ? documents.checkpoint(pack, ownerPath) : null;
                try {
                    for (StagedAsset asset : assets) {
                        backups.put(asset.target(), Backup.capture(asset.target()));
                        Files.createDirectories(asset.target().getParent());
                        Files.write(asset.temp(), asset.bytes());
                    }
                    for (StagedAsset asset : assets) {
                        move(asset.temp(), asset.target());
                    }
                    afterAssetPromotion.run();
                    String actor = "editor:" + RpcDispatcher.currentSessionId();
                    DocumentStore.Document updated = null;
                    if (createOwner) {
                        String ownerId = required(body, "ownerId");
                        String scaffold = Scaffolds.forPath(ownerPath, ownerId);
                        String content = dev.materione.core.document.LosslessYaml.parse(scaffold)
                                .withScalar(required(body, "ownerName"), ownerId, "display_name")
                                .withScalar(modelReference, ownerId, "model")
                                .save();
                        updated = documents.createResource(
                                pack, ownerPath, content, false, actor);
                    } else if (attachOwner) {
                        updated = documents.setScalar(
                                pack, ownerPath, expectedRevision, field, modelReference, actor);
                    }
                    audit.record(
                            actor,
                            "authoring.saveModel",
                            pack + (attachOwner ? "/" + ownerPath : "")
                                    + " (" + assets.size() + " assets)");
                    reload.reloadScopeForPreview(
                            new ReloadScope.OnePack(pack),
                            RpcDispatcher.currentSessionId());
                    JsonObject payload = new JsonObject();
                    if (updated != null) {
                        payload.addProperty("rev", updated.revision());
                        payload.addProperty("raw", updated.raw());
                        payload.addProperty("ownerPath", ownerPath);
                    }
                    return Wire.Response.success(request, payload);
                } catch (Exception failure) {
                    restore(backups);
                    if (ownerCheckpoint != null) {
                        documents.restore(ownerCheckpoint);
                    }
                    throw failure;
                } finally {
                    for (StagedAsset asset : assets) {
                        Files.deleteIfExists(asset.temp());
                    }
                }
                }
            } catch (DocumentConflictException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0034", e.getMessage(), pack + "/" + ownerPath,
                        "return to the item and reopen its model"));
            } catch (IllegalArgumentException | IllegalStateException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0035", e.getMessage(), pack + "/" + ownerPath, null));
            } catch (Exception e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0011", "model transaction failed: " + e.getMessage(),
                        pack + "/" + ownerPath, "no partial model changes were kept"));
            }
        });
    }

    private static List<StagedAsset> assets(
            String pack, Path packsRoot, JsonArray values) {
        if (values == null || values.size() > 128) {
            throw new IllegalArgumentException("model transaction has too many assets");
        }
        List<StagedAsset> assets = new ArrayList<>();
        var targets = new HashSet<Path>();
        int total = 0;
        Path packRoot = packsRoot.resolve(pack).normalize();
        for (var value : values) {
            JsonObject entry = value.getAsJsonObject();
            String relative = required(entry, "path").replace('\\', '/');
            if (!relative.matches("assets/[A-Za-z0-9_./ -]+") || relative.contains("..")) {
                throw new IllegalArgumentException("invalid model asset path: " + relative);
            }
            byte[] bytes = Base64.getDecoder().decode(required(entry, "data"));
            total += bytes.length;
            if (total > MAX_TRANSACTION_BYTES) {
                throw new IllegalArgumentException("model transaction exceeds 8 MiB");
            }
            Path target = packRoot.resolve(relative).normalize();
            if (!target.startsWith(packRoot)) {
                throw new IllegalArgumentException("model asset escapes its pack");
            }
            if (!targets.add(target)) {
                throw new IllegalArgumentException("duplicate model asset path: " + relative);
            }
            if (Files.exists(target) && !Files.isRegularFile(target)) {
                throw new IllegalArgumentException("model asset target is not a regular file: " + relative);
            }
            rejectSymlinkPath(packRoot, target);
            Path temp = target.resolveSibling(target.getFileName() + ".model-tmp-" + System.nanoTime());
            assets.add(new StagedAsset(target, temp, bytes));
        }
        return assets;
    }

    private static void rejectSymlinkPath(Path packRoot, Path target) {
        for (Path path = target; path != null && path.startsWith(packRoot); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) {
                throw new IllegalArgumentException("model asset path crosses a symbolic link");
            }
            if (path.equals(packRoot)) {
                return;
            }
        }
    }

    private static StagedAsset relationship(
            Path packRoot,
            String ownerPath,
            String field,
            String modelReference,
            String sourcePath) throws java.io.IOException {
        Path target = packRoot.resolve(".materione").resolve("model-links.json");
        rejectSymlinkPath(packRoot, target);
        JsonObject manifest = new JsonObject();
        JsonObject links = new JsonObject();
        if (Files.isRegularFile(target)) {
            try {
                manifest = JsonParser.parseString(Files.readString(target)).getAsJsonObject();
                if (manifest.has("links") && manifest.get("links").isJsonObject()) {
                    links = manifest.getAsJsonObject("links");
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("model relationship manifest is invalid", e);
            }
        }
        manifest.addProperty("version", 1);
        JsonObject link = new JsonObject();
        link.addProperty("model", modelReference);
        if (!sourcePath.isBlank()) {
            link.addProperty("source", sourcePath);
        }
        links.add(ownerPath + "#" + field, link);
        manifest.add("links", links);
        byte[] bytes = (new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(manifest) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path temp = target.resolveSibling(target.getFileName() + ".model-tmp-" + System.nanoTime());
        return new StagedAsset(target, temp, bytes);
    }

    private static void restore(Map<Path, Backup> backups) throws java.io.IOException {
        for (var entry : backups.entrySet()) {
            if (entry.getValue().existed()) {
                Files.write(entry.getKey(), entry.getValue().bytes());
            } else {
                Files.deleteIfExists(entry.getKey());
            }
        }
    }

    private static void move(Path from, Path to) throws java.io.IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
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

    private record StagedAsset(Path target, Path temp, byte[] bytes) {}

    private record Backup(boolean existed, byte[] bytes) {
        private static Backup capture(Path path) throws java.io.IOException {
            return Files.isRegularFile(path)
                    ? new Backup(true, Files.readAllBytes(path))
                    : new Backup(false, new byte[0]);
        }
    }
}
