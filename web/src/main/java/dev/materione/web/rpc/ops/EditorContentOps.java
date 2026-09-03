package dev.materione.web.rpc.ops;

import com.google.gson.JsonObject;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentConflictException;
import dev.materione.core.document.DocumentStore;
import dev.materione.web.ReloadTrigger;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import dev.materione.web.TypeMeta;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The document-store RPC surface (web.md §4.2 groups browse/edit/validate).
 * Handlers stay thin: they translate JSON ↔ {@link DocumentStore} records and
 * never touch files themselves — the store owns atomicity, revisions and
 * conflict detection; the host's {@link ReloadTrigger} closes the live
 * preview loop after each successful save.
 *
 * <p>Errors reuse diagnostic codes as strings (the wire error IS a rendered
 * diagnostic, plugin.md §5): MTR-0007 syntax, MTR-0034 conflict, MTR-0011
 * handler failure.
 */
public final class EditorContentOps {

    private EditorContentOps() {}

    /**
     * Registers the ops. {@code packsRoot} is only needed to hand absolute
     * file paths to the reload trigger; the suppliers feed {@code browse.types}
     * (registry self-description → forms) and the diagnostics center.
     */
    public static void register(
            RpcDispatcher dispatcher,
            DocumentStore documents,
            AuditLog audit,
            Path packsRoot,
            ReloadTrigger reload,
            java.util.function.Supplier<List<TypeMeta>> types,
            java.util.function.Supplier<List<dev.materione.api.diagnostics.Diagnostic>> diagnostics) {
        dispatcher.register("browse.packs", request -> {
            var payload = new JsonObject();
            var packs = new com.google.gson.JsonArray();
            documents.listPacks().forEach(pack -> packs.add(pack.id()));
            payload.add("packs", packs);
            return Wire.Response.success(request, payload);
        });

        dispatcher.register("browse.resources", request -> {
            String pack = optionalString(request.payload(), "pack");
            String type = optionalString(request.payload(), "type");
            String query = optionalString(request.payload(), "query");
            var payload = new JsonObject();
            var resources = new com.google.gson.JsonArray();
            for (DocumentStore.ResourceRef ref : documents.listResources(pack, type, query)) {
                JsonObject resource = new JsonObject();
                resource.addProperty("pack", ref.pack());
                resource.addProperty("type", ref.type());
                resource.addProperty("path", ref.path());
                resource.addProperty("id", ref.resourceId());
                resources.add(resource);
            }
            payload.add("resources", resources);
            return Wire.Response.success(request, payload);
        });

        dispatcher.register("browse.get", request -> {
            try {
                DocumentStore.Document document = documents.read(
                        requiredString(request.payload(), "pack"),
                        requiredString(request.payload(), "path"));
                JsonObject payload = new JsonObject();
                payload.addProperty("raw", document.raw());
                payload.addProperty("rev", document.revision());
                payload.addProperty("sha256", document.sha256());
                payload.add("parsed", parsedView(document.raw()));
                return Wire.Response.success(request, payload);
            } catch (java.nio.file.NoSuchFileException e) {
                return Wire.Response.failure(request,
                        new Wire.Error("MTR-0009", "no such document", null, null));
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("browse.history", request -> {
            try {
                var limitElement = request.payload().get("limit");
                int limit = limitElement != null && limitElement.isJsonPrimitive()
                        ? limitElement.getAsInt() : 50;
                var revisions = documents.history(
                        requiredString(request.payload(), "pack"),
                        requiredString(request.payload(), "path"),
                        limit);
                JsonObject payload = new JsonObject();
                var array = new com.google.gson.JsonArray();
                for (DocumentStore.Revision revision : revisions) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("rev", revision.rev());
                    entry.addProperty("ts", revision.timestampMs());
                    entry.addProperty("actor", revision.actor());
                    entry.addProperty("action", revision.action());
                    if (revision.sha256() != null) {
                        entry.addProperty("sha256", revision.sha256());
                    }
                    array.add(entry);
                }
                payload.add("revisions", array);
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("edit.put", request -> {
            JsonObject payload0 = request.payload();
            try {
                String pack = requiredString(payload0, "pack");
                String path = requiredString(payload0, "path");
                int expectedRev = payload0.get("rev").getAsInt();
                String content = requiredString(payload0, "content");
                int newRev = documents.write(pack, path, expectedRev, content, actorOf(request));
                audit.record(actorOf(request), "edit.put", pack + "/" + path);
                queueReload(reload, packsRoot.resolve(pack).resolve(path));
                JsonObject payload = new JsonObject();
                payload.addProperty("rev", newRev);
                payload.add("parsed", parsedView(content));
                return Wire.Response.success(request, payload);
            } catch (DocumentConflictException e) {
                audit.record(actorOf(request), "edit.put.conflict",
                        requiredString(payload0, "pack") + "/" + requiredString(payload0, "path"));
                JsonObject data = new JsonObject();
                data.addProperty("currentRev", e.currentRevision());
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0034", e.getMessage(),
                        requiredString(payload0, "pack") + "/" + requiredString(payload0, "path"),
                        "re-read the document and re-apply your change"));
            } catch (IllegalArgumentException e) {
                // syntax gate or path validation — the editor shows it inline
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0007", e.getMessage(),
                        safePath(payload0), null));
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("edit.delete", request -> {
            JsonObject payload0 = request.payload();
            try {
                String pack = requiredString(payload0, "pack");
                String path = requiredString(payload0, "path");
                documents.delete(pack, path, payload0.get("rev").getAsInt(), actorOf(request));
                audit.record(actorOf(request), "edit.delete", pack + "/" + path);
                queueReload(reload, packsRoot.resolve(pack).resolve(path));
                return Wire.Response.success(request, new JsonObject());
            } catch (DocumentConflictException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0034", e.getMessage(), safePath(payload0),
                        "re-read the document and retry"));
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("edit.createPack", request -> {
            try {
                String pack = requiredString(request.payload(), "pack");
                documents.createPack(pack, actorOf(request));
                audit.record(actorOf(request), "edit.createPack", pack);
                return Wire.Response.success(request, new JsonObject());
            } catch (IllegalArgumentException | IllegalStateException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        // Creates a document from the type's curated scaffold; the form takes
        // over from there. Refuses to clobber an existing file.
        dispatcher.register("edit.create", request -> {
            JsonObject body = request.payload();
            try {
                String pack = requiredString(body, "pack");
                String path = requiredString(body, "path");
                String content = Scaffolds.forPath(path, optionalString(body, "id"));
                int rev = documents.createDocument(pack, path, content, actorOf(request));
                audit.record(actorOf(request), "edit.create", pack + "/" + path);
                queueReload(reload, packsRoot.resolve(pack).resolve(path));
                JsonObject payload = new JsonObject();
                payload.addProperty("rev", rev);
                payload.addProperty("raw", content);
                payload.add("parsed", parsedView(content));
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (IllegalStateException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0008", e.getMessage(), safePath(body),
                        "open the existing document instead"));
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("authoring.createResource", request -> {
            JsonObject body = request.payload();
            try {
                String pack = requiredString(body, "pack");
                String id = requiredString(body, "id");
                String name = requiredString(body, "name").trim();
                boolean createPack = body.has("createPack") && body.get("createPack").getAsBoolean();
                if (name.isEmpty() || name.length() > 128) {
                    throw new IllegalArgumentException("display name must be 1..128 characters");
                }
                // The content browser creates inside the type tab (and folder)
                // the author is looking at; items stay the default so older
                // clients keep working.
                String type = optionalString(body, "type");
                String folder = optionalString(body, "folder");
                String path = documentPath(type == null ? "items" : type, folder, id);
                String scaffold = Scaffolds.forPath(path, id);
                // Recipes and templates have no display name; forcing one would
                // invent a field the type's schema does not declare.
                String content = scaffold.contains("display_name:")
                        ? dev.materione.core.document.LosslessYaml.parse(scaffold)
                                .withScalar(name, id, "display_name").save()
                        : scaffold;
                String actor = actorOf(request);
                DocumentStore.Document created =
                        documents.createResource(pack, path, content, createPack, actor);
                audit.record(actor, "authoring.createResource", pack + "/" + path);
                queueReload(reload, packsRoot.resolve(pack).resolve(path));
                JsonObject payload = new JsonObject();
                payload.addProperty("pack", pack);
                payload.addProperty("path", path);
                payload.addProperty("id", id);
                payload.addProperty("rev", created.revision());
                payload.addProperty("raw", created.raw());
                payload.add("parsed", parsedView(created.raw()));
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        // Copying an entry is one create: the scaffold is the source file's
        // bytes with its root key re-keyed, so comments and unknown fields
        // survive the copy exactly like every other authoring write (§5.6).
        dispatcher.register("authoring.duplicateResource", request -> {
            JsonObject body = request.payload();
            try {
                String pack = requiredString(body, "pack");
                String from = requiredString(body, "path");
                String id = requiredString(body, "id");
                String folder = optionalString(body, "folder");
                String type = from.contains("/") ? from.substring(0, from.indexOf('/')) : "";
                String path = documentPath(type, folder, id);
                DocumentStore.Document source = documents.read(pack, from);
                var parsed = dev.materione.core.document.LosslessYaml.parse(source.raw());
                if (parsed.parseError().isPresent()) {
                    throw new IllegalArgumentException(
                            "cannot copy a file that does not parse: " + parsed.parseError().get());
                }
                String sourceId = String.valueOf(parsed.data().keySet().iterator().next());
                String content = source.raw().replaceFirst(
                        "(?m)^" + java.util.regex.Pattern.quote(sourceId) + ":",
                        java.util.regex.Matcher.quoteReplacement(id + ":"));
                String actor = actorOf(request);
                int revision = documents.createDocument(pack, path, content, actor);
                audit.record(actor, "authoring.duplicateResource", pack + "/" + path);
                queueReload(reload, packsRoot.resolve(pack).resolve(path));
                JsonObject payload = new JsonObject();
                payload.addProperty("pack", pack);
                payload.addProperty("path", path);
                payload.addProperty("id", id);
                payload.addProperty("rev", revision);
                payload.addProperty("raw", content);
                payload.add("parsed", parsedView(content));
                return Wire.Response.success(request, payload);
            } catch (java.nio.file.NoSuchFileException e) {
                return Wire.Response.failure(request,
                        new Wire.Error("MTR-0009", "no such document", safePath(body), null));
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (IllegalStateException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0008", e.getMessage(), safePath(body),
                        "choose a different id"));
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("browse.folders", request -> {
            JsonObject payload = new JsonObject();
            var folders = new com.google.gson.JsonArray();
            documents.listFolders(requiredString(request.payload(), "pack")).forEach(folders::add);
            payload.add("folders", folders);
            return Wire.Response.success(request, payload);
        });

        dispatcher.register("edit.createFolder", request -> {
            JsonObject body = request.payload();
            try {
                String pack = requiredString(body, "pack");
                String path = requiredString(body, "path");
                documents.createFolder(pack, path);
                audit.record(actorOf(request), "edit.createFolder", pack + "/" + path);
                return Wire.Response.success(request, new JsonObject());
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (IllegalStateException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0008", e.getMessage(), safePath(body), null));
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("edit.deleteFolder", request -> {
            JsonObject body = request.payload();
            try {
                String pack = requiredString(body, "pack");
                String path = requiredString(body, "path");
                documents.deleteFolder(pack, path);
                audit.record(actorOf(request), "edit.deleteFolder", pack + "/" + path);
                return Wire.Response.success(request, new JsonObject());
            } catch (java.nio.file.NoSuchFileException e) {
                return Wire.Response.failure(request,
                        new Wire.Error("MTR-0009", "no such folder", safePath(body), null));
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (IllegalStateException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0008", e.getMessage(), safePath(body),
                        "empty the folder first"));
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("edit.moveFolder", request -> {
            JsonObject body = request.payload();
            try {
                String pack = requiredString(body, "pack");
                String from = requiredString(body, "from");
                String to = requiredString(body, "to");
                documents.moveFolder(pack, from, to, actorOf(request));
                audit.record(actorOf(request), "edit.moveFolder", pack + "/" + from + " -> " + to);
                queueReload(reload, packsRoot.resolve(pack));
                return Wire.Response.success(request, new JsonObject());
            } catch (DocumentConflictException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0034", e.getMessage(), safePath(body),
                        "reload the pack and try again"));
            } catch (java.nio.file.NoSuchFileException e) {
                return Wire.Response.failure(request,
                        new Wire.Error("MTR-0009", "no such folder", safePath(body), null));
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (IllegalStateException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0008", e.getMessage(), safePath(body), null));
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("edit.move", request -> {
            JsonObject body = request.payload();
            try {
                String pack = requiredString(body, "pack");
                String from = requiredString(body, "from");
                String to = requiredString(body, "to");
                DocumentStore.Document moved =
                        documents.move(pack, from, body.get("rev").getAsInt(), to, actorOf(request));
                audit.record(actorOf(request), "edit.move", pack + "/" + from + " -> " + to);
                queueReload(reload, packsRoot.resolve(pack).resolve(from));
                queueReload(reload, packsRoot.resolve(pack).resolve(to));
                JsonObject payload = new JsonObject();
                payload.addProperty("rev", moved.revision());
                payload.add("parsed", parsedView(moved.raw()));
                return Wire.Response.success(request, payload);
            } catch (DocumentConflictException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0034", e.getMessage(), safePath(body),
                        "re-read the document and retry"));
            } catch (java.nio.file.NoSuchFileException e) {
                return Wire.Response.failure(request,
                        new Wire.Error("MTR-0009", "no such document: " + e.getFile(), null, null));
            } catch (IllegalArgumentException | IllegalStateException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("validate.dryRun", request -> {
            // v1: syntax-level gate (what write would reject); semantic
            // validation streams back from the pipeline run that follows.
            String content = requiredString(request.payload(), "content");
            var parseError = dev.materione.core.document.LosslessYaml.parse(content).parseError();
            JsonObject payload = new JsonObject();
            if (parseError.isPresent()) {
                payload.addProperty("ok", false);
                payload.addProperty("error", parseError.get());
            } else {
                payload.addProperty("ok", true);
            }
            return Wire.Response.success(request, payload);
        });

        dispatcher.register("edit.setScalar", request -> {
            JsonObject body = request.payload();
            try {
                DocumentStore.Document document = documents.setScalar(
                        requiredString(body, "pack"),
                        requiredString(body, "path"),
                        body.get("rev").getAsInt(),
                        stringList(body.get("field")),
                        valueOf(body.get("value")),
                        actorOf(request));
                audit.record(actorOf(request), "edit.setScalar",
                        requiredString(body, "pack") + "/" + requiredString(body, "path"));
                queueReload(reload, packsRoot.resolve(requiredString(body, "pack"))
                        .resolve(requiredString(body, "path")));
                JsonObject payload = new JsonObject();
                payload.addProperty("rev", document.revision());
                payload.addProperty("raw", document.raw());
                return Wire.Response.success(request, payload);
            } catch (DocumentConflictException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0034", e.getMessage(), safePath(body),
                        "re-read the document and retry"));
            } catch (IllegalArgumentException | IllegalStateException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        // The Item Studio commits a coherent draft through the same lossless
        // document store in one revision and queues exactly one reload.
        dispatcher.register("authoring.commit", request -> {
            JsonObject body = request.payload();
            try {
                String pack = requiredString(body, "pack");
                String path = requiredString(body, "path");
                int revision = body.get("rev").getAsInt();
                String content = requiredString(body, "content");
                int newRevision = documents.write(pack, path, revision, content, actorOf(request));
                audit.record(actorOf(request), "authoring.commit", pack + "/" + path);
                queueReload(reload, packsRoot.resolve(pack).resolve(path));
                JsonObject payload = new JsonObject();
                payload.addProperty("rev", newRevision);
                payload.addProperty("raw", content);
                payload.add("parsed", parsedView(content));
                return Wire.Response.success(request, payload);
            } catch (DocumentConflictException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0034", e.getMessage(), safePath(body),
                        "re-read the item and re-apply your changes"));
            } catch (IllegalArgumentException e) {
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0007", e.getMessage(), safePath(body), null));
            } catch (Exception e) {
                return failure(request, e);
            }
        });
        dispatcher.register("browse.types", request -> {
            JsonObject payload = new JsonObject();
            var array = new com.google.gson.JsonArray();
            for (TypeMeta type : types.get()) {
                array.add(typeJson(type));
            }
            payload.add("types", array);
            return Wire.Response.success(request, payload);
        });

        dispatcher.register("admin.diagnostics.recent", request -> {
            JsonObject payload = new JsonObject();
            var array = new com.google.gson.JsonArray();
            for (dev.materione.api.diagnostics.Diagnostic diagnostic : diagnostics.get()) {
                array.add(dev.materione.web.SessionPush.entryJson(diagnostic));
            }
            payload.add("diagnostics", array);
            return Wire.Response.success(request, payload);
        });
    }

    private static JsonObject typeJson(TypeMeta type) {
        JsonObject object = new JsonObject();
        object.addProperty("name", type.name());
        if (type.directory() != null) {
            object.addProperty("directory", type.directory());
        }
        object.addProperty("displayName", type.displayName());
        object.addProperty("description", type.description());
        var params = new com.google.gson.JsonArray();
        type.parameters().forEach(param -> params.add(paramJson(param)));
        object.add("parameters", params);
        return object;
    }

    private static JsonObject paramJson(TypeMeta.Param param) {
        JsonObject object = new JsonObject();
        object.addProperty("id", param.id());
        object.addProperty("type", param.type());
        object.addProperty("required", param.required());
        if (param.defaultValue() != null) {
            object.addProperty("default", param.defaultValue());
        }
        if (param.description() != null) {
            object.addProperty("description", param.description());
        }
        if (!param.allowedValues().isEmpty()) {
            var values = new com.google.gson.JsonArray();
            param.allowedValues().forEach(values::add);
            object.add("allowedValues", values);
        }
        if (!param.nested().isEmpty()) {
            var nested = new com.google.gson.JsonArray();
            param.nested().forEach(param1 -> nested.add(paramJson(param1)));
            object.add("nested", nested);
        }
        if (param.uiHint() != null) {
            object.addProperty("uiHint", param.uiHint());
        }
        object.addProperty("label", param.label());
        object.addProperty("section", param.section());
        object.addProperty("order", param.order());
        object.addProperty("advanced", param.advanced());
        if (!param.referenceKind().isBlank()) {
            object.addProperty("referenceKind", param.referenceKind());
        }
        if (!param.example().isBlank()) {
            object.addProperty("example", param.example());
        }
        return object;
    }

    private static List<String> stringList(com.google.gson.JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException("'path' must be an array of field names");
        }
        List<String> path = new java.util.ArrayList<>();
        for (var item : element.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) {
                throw new IllegalArgumentException("'path' entries must be strings");
            }
            path.add(item.getAsString());
        }
        return path;
    }

    /** Form values arrive as JSON scalars (or arrays of them for lists). */
    private static Object valueOf(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("'value' is required");
        }
        if (element.isJsonArray()) {
            List<Object> items = new java.util.ArrayList<>();
            for (var item : element.getAsJsonArray()) {
                if (!item.isJsonPrimitive()) {
                    throw new IllegalArgumentException("list 'value' entries must be scalars");
                }
                items.add(primitiveOf(item.getAsJsonPrimitive()));
            }
            return items;
        }
        if (!element.isJsonPrimitive()) {
            throw new IllegalArgumentException("'value' must be a scalar or list of scalars");
        }
        return primitiveOf(element.getAsJsonPrimitive());
    }

    private static Object primitiveOf(com.google.gson.JsonPrimitive primitive) {
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsNumber();
        }
        return primitive.getAsString();
    }

    // ------------------------------------------------------------------

    /**
     * Builds the pack-relative path for a new document from the type tab and
     * the folder the author is browsing. Both are validated here so a hostile
     * client cannot smuggle a path outside the type's directory.
     */
    private static String documentPath(String type, String folder, String id) {
        if (!Scaffolds.SCAFFOLD_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "unknown content type '" + type + "' — expected one of "
                            + Scaffolds.SCAFFOLD_TYPES);
        }
        if (!id.matches("[a-z0-9_-]{2,64}")) {
            throw new IllegalArgumentException("id must be 2..64 chars of a-z 0-9 _ -");
        }
        String prefix = type;
        if (folder != null && !folder.isBlank()) {
            String cleaned = folder.replaceAll("^/+|/+$", "");
            if (cleaned.startsWith(type + "/")) {
                cleaned = cleaned.substring(type.length() + 1);
            } else if (cleaned.equals(type)) {
                cleaned = "";
            }
            if (!cleaned.isEmpty()) {
                if (!cleaned.matches("[A-Za-z0-9_ -]+(/[A-Za-z0-9_ -]+)*")) {
                    throw new IllegalArgumentException("bad folder: " + folder);
                }
                prefix = type + "/" + cleaned;
            }
        }
        return prefix + "/" + id + ".yml";
    }

    private static void queueReload(ReloadTrigger reload, Path file) {
        // Fire-and-forget at v1: the reload outcome reaches editors through
        // the activity feed (slice 5's push channel), not through this reply.
        reload.reloadFilesForPreview(
                Set.of(file), RpcDispatcher.currentSessionId())
                .whenComplete((outcome, error) -> { });
    }

    /** The data view for form pre-fill — scalars/maps/lists as JSON. */
    private static com.google.gson.JsonElement parsedView(String raw) {
        try {
            var parsed = dev.materione.core.document.LosslessYaml.parse(raw);
            return jsonOf(parsed.parseError().isEmpty() ? parsed.data() : java.util.Map.of());
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    @SuppressWarnings("unchecked")
    private static com.google.gson.JsonElement jsonOf(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            JsonObject object = new JsonObject();
            for (var entry : ((java.util.Map<Object, Object>) map).entrySet()) {
                object.add(String.valueOf(entry.getKey()), jsonOf(entry.getValue()));
            }
            return object;
        }
        if (value instanceof java.util.List<?> list) {
            var array = new com.google.gson.JsonArray();
            list.forEach(item -> array.add(jsonOf(item)));
            return array;
        }
        if (value instanceof String text) {
            return new com.google.gson.JsonPrimitive(text);
        }
        if (value instanceof Number number) {
            return new com.google.gson.JsonPrimitive(number);
        }
        if (value instanceof Boolean flag) {
            return new com.google.gson.JsonPrimitive(flag);
        }
        if (value == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        return new com.google.gson.JsonPrimitive(value.toString());
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

    private static String safePath(JsonObject payload) {
        try {
            return requiredString(payload, "pack") + "/" + requiredString(payload, "path");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String requiredString(JsonObject object, String field) {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing field '" + field + "'");
        }
        return value.getAsString();
    }

    private static String optionalString(JsonObject object, String field) {
        var value = object.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
