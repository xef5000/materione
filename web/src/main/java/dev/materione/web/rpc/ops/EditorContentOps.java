package dev.materione.web.rpc.ops;

import com.google.gson.JsonObject;
import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.content.ContentType;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.RegistryEntry;
import dev.materione.api.registry.SelfDescription;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The document-store RPC surface (web.md §4.2 groups browse/edit/validate).
 * Handlers are pure JSON translators: request frames → typed {@link
 * AuthoringApi} calls → response frames. Every file mutation, revision
 * journal, audit entry and reload trigger lives behind the API (the plugin
 * is an implementation of the API — api.md §1); this layer owns nothing but
 * the wire shapes.
 *
 * <p>Errors reuse diagnostic codes as strings (the wire error IS a rendered
 * diagnostic, plugin.md §5): MTR-0007 syntax, MTR-0034 conflict, MTR-0011
 * handler failure.
 */
public final class EditorContentOps {

    private EditorContentOps() {}

    /**
     * Registers the ops. {@code actorFor} maps this op set's audit
     * attribution string to the acting {@link ActorContext}; {@code types}
     * feeds {@code browse.types} (registry self-description → forms).
     */
    public static void register(
            RpcDispatcher dispatcher,
            AuthoringApi authoring,
            Supplier<List<ContentType>> types,
            Supplier<List<Diagnostic>> diagnostics,
            Function<String, ActorContext> actorFor) {
        register(dispatcher, authoring, types, diagnostics, actorFor,
                dev.materione.api.registry.BehaviorVocabulary::empty);
    }

    public static void register(
            RpcDispatcher dispatcher,
            AuthoringApi authoring,
            Supplier<List<ContentType>> types,
            Supplier<List<Diagnostic>> diagnostics,
            Function<String, ActorContext> actorFor,
            Supplier<dev.materione.api.registry.BehaviorVocabulary> vocabulary) {
        register(dispatcher, authoring, types, diagnostics, actorFor, vocabulary, List::of);
    }

    /**
     * Registers the ops with the GUI component registry as well: {@code guiComponents}
     * is read from the {@code gui-components} registry entries, on each describe call.
     */
    public static void register(
            RpcDispatcher dispatcher,
            AuthoringApi authoring,
            Supplier<List<ContentType>> types,
            Supplier<List<Diagnostic>> diagnostics,
            Function<String, ActorContext> actorFor,
            Supplier<dev.materione.api.registry.BehaviorVocabulary> vocabulary,
            Supplier<List<RegistryEntry<?>>> guiComponents) {
        // Capability discovery (web.md §4.2): content types and the behavior
        // vocabulary, each read from where it is registered — never a copy.
        dispatcher.register("authoring.describe", request -> {
            JsonObject payload = new JsonObject();
            var array = new com.google.gson.JsonArray();
            for (ContentType type : types.get()) {
                array.add(typeJson(type));
            }
            payload.add("types", array);
            payload.add("behavior", vocabularyJson(vocabulary.get()));
            payload.add("guiComponents", guiComponentsJson(guiComponents.get()));
            return Wire.Response.success(request, payload);
        });

        dispatcher.register("browse.packs", request -> {
            var payload = new JsonObject();
            var packs = new com.google.gson.JsonArray();
            authoring.listPacks().value().forEach(pack -> packs.add(pack.id()));
            payload.add("packs", packs);
            return Wire.Response.success(request, payload);
        });

        dispatcher.register("browse.resources", request -> {
            String pack = optionalString(request.payload(), "pack");
            String type = optionalString(request.payload(), "type");
            String query = optionalString(request.payload(), "query");
            var payload = new JsonObject();
            var resources = new com.google.gson.JsonArray();
            for (AuthoringApi.ResourceRef ref : authoring.listResources(
                    new AuthoringApi.ResourceFilter(pack, type, query)).value()) {
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

        dispatcher.register("authoring.summaries", request -> {
            try {
                JsonObject body = request.payload();
                String pack = requiredString(body, "pack");
                String type = optionalString(body, "type");
                String query = optionalString(body, "query");
                int offset = optionalInteger(body, "offset", 0);
                int limit = optionalInteger(body, "limit", 100);
                ApiResult<AuthoringApi.ResourceSummaryPage> result =
                        authoring.listResourceSummaries(
                                new AuthoringApi.ResourceFilter(pack, type, query), offset, limit);
                if (!result.ok()) {
                    return error(request, result.diagnostics(), pack, null);
                }
                AuthoringApi.ResourceSummaryPage page = result.value();
                JsonObject payload = new JsonObject();
                var resources = new com.google.gson.JsonArray();
                for (AuthoringApi.ResourceSummary summary : page.resources()) {
                    JsonObject resource = new JsonObject();
                    resource.addProperty("pack", summary.pack());
                    resource.addProperty("type", summary.type());
                    resource.addProperty("path", summary.path());
                    resource.addProperty("id", summary.resourceId());
                    resource.addProperty("name", summary.name());
                    if (summary.material() != null) {
                        resource.addProperty("material", summary.material());
                    }
                    if (summary.visual() != null) {
                        JsonObject visual = new JsonObject();
                        visual.addProperty("pack", summary.visual().pack());
                        visual.addProperty("path", summary.visual().path());
                        visual.addProperty("kind", summary.visual().kind() == AuthoringApi.VisualKind.TEXTURE
                                ? "texture" : "model-thumbnail");
                        resource.add("visual", visual);
                    }
                    if (summary.render() != null) {
                        JsonObject render = new JsonObject();
                        render.addProperty("pack", summary.render().pack());
                        if (summary.render().path() != null) {
                            render.addProperty("path", summary.render().path());
                        } else {
                            render.add("model", jsonOf(summary.render().model()));
                        }
                        resource.add("render", render);
                    }
                    resource.addProperty("health",
                            summary.health().name().toLowerCase(java.util.Locale.ROOT));
                    if (summary.healthMessage() != null) {
                        resource.addProperty("healthMessage", summary.healthMessage());
                    }
                    resources.add(resource);
                }
                payload.add("resources", resources);
                payload.addProperty("total", page.total());
                if (page.nextOffset() != null) {
                    payload.addProperty("nextOffset", page.nextOffset());
                }
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("browse.get", request -> {
            try {
                ApiResult<AuthoringApi.DocumentView> result = authoring.getDocument(
                        requiredString(request.payload(), "pack"),
                        requiredString(request.payload(), "path"));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), null, null);
                }
                AuthoringApi.DocumentView document = result.value();
                JsonObject payload = new JsonObject();
                payload.addProperty("raw", document.raw());
                payload.addProperty("rev", document.revision());
                payload.addProperty("sha256", document.sha256());
                payload.add("parsed", jsonOf(document.parsed()));
                if (!document.computed().isEmpty()) {
                    payload.add("computed", jsonOf(document.computed()));
                }
                return Wire.Response.success(request, payload);
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("browse.history", request -> {
            try {
                var limitElement = request.payload().get("limit");
                int limit = limitElement != null && limitElement.isJsonPrimitive()
                        ? limitElement.getAsInt() : 50;
                ApiResult<List<AuthoringApi.RevisionInfo>> result = authoring.getHistory(
                        requiredString(request.payload(), "pack"),
                        requiredString(request.payload(), "path"),
                        limit);
                if (!result.ok()) {
                    return error(request, result.diagnostics(), null, null);
                }
                JsonObject payload = new JsonObject();
                var array = new com.google.gson.JsonArray();
                for (AuthoringApi.RevisionInfo revision : result.value()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("rev", revision.revision());
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
                ApiResult<AuthoringApi.DocumentView> result = authoring.putDocument(
                        actorFor.apply(actorOf(request)),
                        new AuthoringApi.PutDocumentRequest(pack, path, expectedRev, content));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), safePath(payload0), null);
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("rev", result.value().revision());
                payload.add("parsed", jsonOf(result.value().parsed()));
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                // malformed frame — the editor shows it inline
                return Wire.Response.failure(request, new Wire.Error(
                        "MTR-0007", e.getMessage(), safePath(payload0), null));
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("edit.delete", request -> {
            JsonObject payload0 = request.payload();
            try {
                ApiResult<Void> result = authoring.deleteDocument(
                        actorFor.apply(actorOf(request)),
                        new AuthoringApi.DeleteDocumentRequest(
                                requiredString(payload0, "pack"),
                                requiredString(payload0, "path"),
                                payload0.get("rev").getAsInt()));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), safePath(payload0), null);
                }
                return Wire.Response.success(request, new JsonObject());
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("edit.createPack", request -> {
            try {
                ApiResult<AuthoringApi.PackInfo> result = authoring.createPack(
                        actorFor.apply(actorOf(request)),
                        requiredString(request.payload(), "pack"));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), null, null);
                }
                return Wire.Response.success(request, new JsonObject());
            } catch (IllegalArgumentException e) {
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
                ApiResult<AuthoringApi.DocumentView> result = authoring.createDocument(
                        actorFor.apply(actorOf(request)),
                        new AuthoringApi.CreateDocumentRequest(
                                requiredString(body, "pack"),
                                requiredString(body, "path"),
                                optionalString(body, "id"),
                                null,
                                null));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), safePath(body),
                            hintFor(result.diagnostics(), "open the existing document instead"));
                }
                AuthoringApi.DocumentView created = result.value();
                JsonObject payload = new JsonObject();
                payload.addProperty("rev", created.revision());
                payload.addProperty("raw", created.raw());
                payload.add("parsed", jsonOf(created.parsed()));
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        // One studio save = one revision, one audit line, one reload. The
        // edits ride the api's lossless layer, so untouched keys, comments and
        // fields no studio can render survive verbatim (§5.6).
        dispatcher.register("authoring.setFields", request -> {
            JsonObject body = request.payload();
            try {
                var element = body.get("edits");
                if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
                    throw new IllegalArgumentException("'edits' must be a non-empty array");
                }
                if (element.getAsJsonArray().size() > 256) {
                    throw new IllegalArgumentException("too many edits in one save");
                }
                List<AuthoringApi.FieldEdit> edits = parseEdits(element);
                List<AuthoringApi.ModelAsset> assets = parseAssets(body.get("assets"));
                ApiResult<AuthoringApi.DocumentView> result = authoring.setFields(
                        actorFor.apply(actorOf(request)),
                        new AuthoringApi.SetFieldsRequest(
                                requiredString(body, "pack"),
                                requiredString(body, "path"),
                                body.get("rev").getAsInt(),
                                edits,
                                assets));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), safePath(body),
                            hintFor(result.diagnostics(), "reopen the entry and re-apply your change"));
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("rev", result.value().revision());
                payload.addProperty("raw", result.value().raw());
                payload.add("parsed", jsonOf(result.value().parsed()));
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        // Where is this used? Typed (asset | resource), bounded, and honest
        // about what it did not read (coverage), so the editor never shows an
        // empty list as proof of "unused".
        dispatcher.register("authoring.references", request -> {
            JsonObject body = request.payload();
            try {
                String kind = requiredString(body, "kind");
                AuthoringApi.ReferenceKind parsedKind = switch (kind) {
                    case "asset" -> AuthoringApi.ReferenceKind.ASSET;
                    case "resource" -> AuthoringApi.ReferenceKind.RESOURCE;
                    default -> throw new IllegalArgumentException("kind must be 'asset' or 'resource'");
                };
                ApiResult<AuthoringApi.ReferenceResult> result = authoring.findReferences(
                        new AuthoringApi.ReferenceQuery(
                                parsedKind, requiredString(body, "pack"), requiredString(body, "target")));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), null, null);
                }
                AuthoringApi.ReferenceResult found = result.value();
                JsonObject payload = new JsonObject();
                com.google.gson.JsonArray hits = new com.google.gson.JsonArray();
                for (AuthoringApi.ReferenceHit hit : found.hits()) {
                    JsonObject row = new JsonObject();
                    row.addProperty("pack", hit.resource().pack());
                    row.addProperty("type", hit.resource().type());
                    row.addProperty("path", hit.resource().path());
                    row.addProperty("id", hit.resource().resourceId());
                    row.add("field", jsonOf(hit.field()));
                    row.addProperty("value", hit.value());
                    hits.add(row);
                }
                payload.add("hits", hits);
                JsonObject coverage = new JsonObject();
                coverage.addProperty("documentsScanned", found.coverage().documentsScanned());
                coverage.addProperty("unreadable", found.coverage().unreadable());
                coverage.addProperty("truncated", found.coverage().truncated());
                coverage.addProperty("complete", found.coverage().complete());
                coverage.add("notScanned", strings(found.coverage().notScanned()));
                payload.add("coverage", coverage);
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        // Revision-checked all-or-none authoring over several documents. The
        // server validates every operation together; dryRun returns the impact
        // summary and touches nothing.
        dispatcher.register("authoring.bulk", request -> {
            JsonObject body = request.payload();
            try {
                var element = body.get("operations");
                if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
                    throw new IllegalArgumentException("'operations' must be a non-empty array");
                }
                if (element.getAsJsonArray().size() > AuthoringApi.MAX_BULK_OPERATIONS) {
                    throw new IllegalArgumentException(
                            "at most " + AuthoringApi.MAX_BULK_OPERATIONS + " documents per bulk change");
                }
                List<AuthoringApi.BulkOperation> operations = new java.util.ArrayList<>();
                for (var entry : element.getAsJsonArray()) {
                    if (!entry.isJsonObject()) {
                        throw new IllegalArgumentException("'operations' entries must be objects");
                    }
                    JsonObject op = entry.getAsJsonObject();
                    AuthoringApi.BulkKind kind = switch (requiredString(op, "kind")) {
                        case "set" -> AuthoringApi.BulkKind.SET;
                        case "create" -> AuthoringApi.BulkKind.CREATE;
                        case "duplicate" -> AuthoringApi.BulkKind.DUPLICATE;
                        default -> throw new IllegalArgumentException(
                                "operation kind must be set, create or duplicate");
                    };
                    var rawEdits = op.get("edits");
                    operations.add(new AuthoringApi.BulkOperation(
                            kind,
                            optionalString(op, "path"),
                            op.has("rev") ? op.get("rev").getAsInt() : -1,
                            optionalString(op, "type"),
                            optionalString(op, "folder"),
                            optionalString(op, "id"),
                            optionalString(op, "name"),
                            optionalString(op, "fromPath"),
                            rawEdits == null || rawEdits.isJsonNull()
                                    ? List.of() : parseEdits(rawEdits)));
                }
                ApiResult<AuthoringApi.BulkResult> result = authoring.bulk(
                        actorFor.apply(actorOf(request)),
                        new AuthoringApi.BulkRequest(
                                requiredString(body, "pack"), operations,
                                body.has("dryRun") && body.get("dryRun").getAsBoolean()));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), null,
                            hintFor(result.diagnostics(), "reload the affected documents and review the change again"));
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("applied", result.value().applied());
                com.google.gson.JsonArray documents = new com.google.gson.JsonArray();
                for (AuthoringApi.BulkDocumentResult doc : result.value().documents()) {
                    JsonObject row = new JsonObject();
                    row.addProperty("path", doc.path());
                    row.addProperty("kind", doc.kind().name().toLowerCase(java.util.Locale.ROOT));
                    row.addProperty("rev", doc.revision());
                    row.addProperty("unchanged", doc.unchanged());
                    com.google.gson.JsonArray changes = new com.google.gson.JsonArray();
                    for (AuthoringApi.BulkFieldChange change : doc.changes()) {
                        JsonObject item = new JsonObject();
                        item.add("field", jsonOf(change.field()));
                        item.add("before", jsonOf(change.before()));
                        item.add("after", jsonOf(change.after()));
                        changes.add(item);
                    }
                    row.add("changes", changes);
                    documents.add(row);
                }
                payload.add("documents", documents);
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("authoring.createResource", request -> {
            JsonObject body = request.payload();
            try {
                String name = requiredString(body, "name").trim();
                if (name.isEmpty() || name.length() > 128) {
                    throw new IllegalArgumentException("display name must be 1..128 characters");
                }
                String id = requiredString(body, "id");
                ApiResult<AuthoringApi.DocumentView> result = authoring.createResource(
                        actorFor.apply(actorOf(request)),
                        new AuthoringApi.CreateResourceRequest(
                                requiredString(body, "pack"),
                                id,
                                name,
                                optionalString(body, "type"),
                                optionalString(body, "folder"),
                                body.has("createPack") && body.get("createPack").getAsBoolean(),
                                optionalString(body, "template"),
                                optionalString(body, "serves")));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), null, null);
                }
                AuthoringApi.DocumentView created = result.value();
                JsonObject payload = new JsonObject();
                payload.addProperty("pack", created.pack());
                payload.addProperty("path", created.path());
                payload.addProperty("id", id);
                payload.addProperty("rev", created.revision());
                payload.addProperty("raw", created.raw());
                payload.add("parsed", jsonOf(created.parsed()));
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        // Copying an entry is one create: the api re-keys the source file's
        // bytes, so comments and unknown fields survive the copy (§5.6).
        dispatcher.register("authoring.duplicateResource", request -> {
            JsonObject body = request.payload();
            try {
                String id = requiredString(body, "id");
                ApiResult<AuthoringApi.DocumentView> result = authoring.duplicateResource(
                        actorFor.apply(actorOf(request)),
                        new AuthoringApi.DuplicateResourceRequest(
                                requiredString(body, "pack"),
                                requiredString(body, "path"),
                                id,
                                optionalString(body, "folder")));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), safePath(body),
                            hintFor(result.diagnostics(), "choose a different id"));
                }
                AuthoringApi.DocumentView created = result.value();
                JsonObject payload = new JsonObject();
                payload.addProperty("pack", created.pack());
                payload.addProperty("path", created.path());
                payload.addProperty("id", id);
                payload.addProperty("rev", created.revision());
                payload.addProperty("raw", created.raw());
                payload.add("parsed", jsonOf(created.parsed()));
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        // Templates are a pack-layout concept, not a registered type (plugin.md
        // §3.1): the editor reads them as one derived catalog — what each sets,
        // which types it serves, who inherits it.
        dispatcher.register("authoring.templates", request -> {
            try {
                ApiResult<List<AuthoringApi.TemplateInfo>> result = authoring.listTemplates();
                if (!result.ok()) {
                    return error(request, result.diagnostics(), null, null);
                }
                var templates = new com.google.gson.JsonArray();
                for (AuthoringApi.TemplateInfo template : result.value()) {
                    JsonObject row = new JsonObject();
                    row.addProperty("pack", template.pack());
                    row.addProperty("path", template.path());
                    row.addProperty("id", template.id());
                    if (template.parent() != null) {
                        row.addProperty("parent", template.parent());
                    }
                    row.add("definition", jsonOf(template.definition()));
                    row.add("serves", strings(template.serves()));
                    var users = new com.google.gson.JsonArray();
                    for (AuthoringApi.ResourceRef user : template.usedBy()) {
                        JsonObject ref = new JsonObject();
                        ref.addProperty("pack", user.pack());
                        ref.addProperty("type", user.type());
                        ref.addProperty("path", user.path());
                        ref.addProperty("id", user.resourceId());
                        users.add(ref);
                    }
                    row.add("usedBy", users);
                    templates.add(row);
                }
                JsonObject payload = new JsonObject();
                payload.add("templates", templates);
                return Wire.Response.success(request, payload);
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        // Detach: the inherited values land in the resource and the link goes,
        // as one revision-guarded write the studio can reconcile from.
        dispatcher.register("authoring.detachTemplate", request -> {
            JsonObject body = request.payload();
            try {
                ApiResult<AuthoringApi.DocumentView> result = authoring.detachTemplate(
                        actorFor.apply(actorOf(request)),
                        new AuthoringApi.DetachTemplateRequest(
                                requiredString(body, "pack"),
                                requiredString(body, "path"),
                                optionalString(body, "resource"),
                                body.get("rev").getAsInt()));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), safePath(body), null);
                }
                AuthoringApi.DocumentView detached = result.value();
                JsonObject payload = new JsonObject();
                payload.addProperty("rev", detached.revision());
                payload.addProperty("raw", detached.raw());
                payload.add("parsed", jsonOf(detached.parsed()));
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("authoring.saveAsTemplate", request -> {
            JsonObject body = request.payload();
            try {
                var fieldsElement = body.get("fields");
                if (fieldsElement == null || !fieldsElement.isJsonArray()) {
                    throw new IllegalArgumentException("'fields' must be an array of field names");
                }
                List<String> fields = new java.util.ArrayList<>();
                fieldsElement.getAsJsonArray().forEach(field -> fields.add(field.getAsString()));
                String id = requiredString(body, "id");
                ApiResult<AuthoringApi.DocumentView> result = authoring.saveAsTemplate(
                        actorFor.apply(actorOf(request)),
                        new AuthoringApi.SaveAsTemplateRequest(
                                requiredString(body, "pack"),
                                requiredString(body, "path"),
                                optionalString(body, "resource"),
                                id,
                                fields));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), safePath(body),
                            hintFor(result.diagnostics(), "choose a different template id"));
                }
                AuthoringApi.DocumentView created = result.value();
                JsonObject payload = new JsonObject();
                payload.addProperty("pack", created.pack());
                payload.addProperty("path", created.path());
                payload.addProperty("id", id);
                payload.addProperty("rev", created.revision());
                payload.addProperty("raw", created.raw());
                payload.add("parsed", jsonOf(created.parsed()));
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("browse.folders", request -> {
            JsonObject payload = new JsonObject();
            var folders = new com.google.gson.JsonArray();
            authoring.listFolders(requiredString(request.payload(), "pack"))
                    .value().forEach(folders::add);
            payload.add("folders", folders);
            return Wire.Response.success(request, payload);
        });

        dispatcher.register("edit.createFolder", request -> {
            JsonObject body = request.payload();
            try {
                ApiResult<Void> result = authoring.createFolder(
                        actorFor.apply(actorOf(request)),
                        requiredString(body, "pack"),
                        requiredString(body, "path"));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), safePath(body), null);
                }
                return Wire.Response.success(request, new JsonObject());
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("edit.deleteFolder", request -> {
            JsonObject body = request.payload();
            try {
                ApiResult<Void> result = authoring.deleteFolder(
                        actorFor.apply(actorOf(request)),
                        requiredString(body, "pack"),
                        requiredString(body, "path"));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), safePath(body),
                            hintFor(result.diagnostics(), "empty the folder first"));
                }
                return Wire.Response.success(request, new JsonObject());
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("edit.moveFolder", request -> {
            JsonObject body = request.payload();
            try {
                ApiResult<Void> result = authoring.moveFolder(
                        actorFor.apply(actorOf(request)),
                        requiredString(body, "pack"),
                        requiredString(body, "from"),
                        requiredString(body, "to"));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), safePath(body), null);
                }
                return Wire.Response.success(request, new JsonObject());
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("edit.move", request -> {
            JsonObject body = request.payload();
            try {
                ApiResult<AuthoringApi.DocumentView> result = authoring.moveDocument(
                        actorFor.apply(actorOf(request)),
                        new AuthoringApi.MoveDocumentRequest(
                                requiredString(body, "pack"),
                                requiredString(body, "from"),
                                body.get("rev").getAsInt(),
                                requiredString(body, "to")));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), safePath(body), null);
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("rev", result.value().revision());
                payload.add("parsed", jsonOf(result.value().parsed()));
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("validate.dryRun", request -> {
            // v1: syntax-level gate (what write would reject); semantic
            // validation streams back from the pipeline run that follows.
            String content = requiredString(request.payload(), "content");
            ApiResult<Void> result = authoring.validateDraft(
                    new AuthoringApi.ValidateDraftRequest(content));
            JsonObject payload = new JsonObject();
            if (!result.ok()) {
                payload.addProperty("ok", false);
                payload.addProperty("error", result.diagnostics().get(0).message());
            } else {
                payload.addProperty("ok", true);
            }
            return Wire.Response.success(request, payload);
        });

        dispatcher.register("edit.setScalar", request -> {
            JsonObject body = request.payload();
            try {
                ApiResult<AuthoringApi.DocumentView> result = authoring.setScalar(
                        actorFor.apply(actorOf(request)),
                        new AuthoringApi.SetScalarRequest(
                                requiredString(body, "pack"),
                                requiredString(body, "path"),
                                body.get("rev").getAsInt(),
                                stringList(body.get("field")),
                                valueOf(body.get("value"))));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), safePath(body), null);
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("rev", result.value().revision());
                payload.addProperty("raw", result.value().raw());
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        // The Item Studio commits a coherent draft through the same lossless
        // api in one revision and queues exactly one reload.
        dispatcher.register("authoring.commit", request -> {
            JsonObject body = request.payload();
            try {
                String pack = requiredString(body, "pack");
                String path = requiredString(body, "path");
                int revision = body.get("rev").getAsInt();
                String content = requiredString(body, "content");
                ApiResult<AuthoringApi.DocumentView> result = authoring.commitDocument(
                        actorFor.apply(actorOf(request)),
                        new AuthoringApi.PutDocumentRequest(pack, path, revision, content));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), safePath(body),
                            hintFor(result.diagnostics(), "re-read the item and re-apply your changes"));
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("rev", result.value().revision());
                payload.addProperty("raw", content);
                payload.add("parsed", jsonOf(result.value().parsed()));
                return Wire.Response.success(request, payload);
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
            for (ContentType type : types.get()) {
                array.add(typeJson(type));
            }
            payload.add("types", array);
            return Wire.Response.success(request, payload);
        });

        dispatcher.register("admin.diagnostics.recent", request -> {
            JsonObject payload = new JsonObject();
            var array = new com.google.gson.JsonArray();
            for (Diagnostic diagnostic : diagnostics.get()) {
                array.add(dev.materione.web.SessionPush.entryJson(diagnostic));
            }
            payload.add("diagnostics", array);
            return Wire.Response.success(request, payload);
        });
    }

    // ------------------------------------------------------------------

    /** Registry self-description → the wire schema shape (identical to the old TypeMeta JSON). */
    private static JsonObject typeJson(ContentType type) {
        SelfDescription description = type.describe();
        JsonObject object = new JsonObject();
        object.addProperty("name", type.name());
        if (type.directory() != null) {
            object.addProperty("directory", type.directory());
        }
        object.addProperty("displayName", description.displayName());
        object.addProperty("description", description.description());
        var params = new com.google.gson.JsonArray();
        description.parameters().forEach(param -> params.add(paramJson(param)));
        object.add("parameters", params);
        return object;
    }

    private static JsonObject vocabularyJson(dev.materione.api.registry.BehaviorVocabulary vocabulary) {
        var safe = vocabulary == null ? dev.materione.api.registry.BehaviorVocabulary.empty() : vocabulary;
        JsonObject object = new JsonObject();
        var events = new com.google.gson.JsonArray();
        for (var event : safe.events()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", event.id());
            json.addProperty("displayName", event.displayName());
            json.addProperty("description", event.description());
            json.add("details", strings(event.details()));
            json.add("firedBy", strings(event.firedBy()));
            events.add(json);
        }
        object.add("events", events);
        var actions = new com.google.gson.JsonArray();
        for (var action : safe.actions()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", action.id());
            if (action.description() != null) {
                describeInto(json, action.description());
            }
            if (action.shorthand() != null) {
                json.addProperty("shorthand", action.shorthand());
            }
            json.addProperty("deferred", action.deferred());
            actions.add(json);
        }
        object.add("actions", actions);
        object.add("ruleOptions", params(safe.ruleOptions()));
        object.add("actionOptions", params(safe.actionOptions()));
        var mechanics = new com.google.gson.JsonArray();
        for (var mechanic : safe.mechanics()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", mechanic.id());
            describeInto(json, mechanic.description());
            json.addProperty("repeatable", mechanic.repeatable());
            json.addProperty("registeredBy", mechanic.registeredBy());
            mechanics.add(json);
        }
        object.add("mechanics", mechanics);
        return object;
    }

    private static void describeInto(JsonObject json, SelfDescription description) {
        json.addProperty("displayName", description.displayName());
        json.addProperty("description", description.description());
        if (description.activation() != null) {
            json.addProperty("activation", description.activation());
        }
        if (description.sinceVersion() != null) {
            json.addProperty("sinceVersion", description.sinceVersion());
        }
        json.add("parameters", params(description.parameters()));
    }

    private static com.google.gson.JsonArray params(List<ParameterSchema> parameters) {
        var array = new com.google.gson.JsonArray();
        parameters.forEach(param -> array.add(paramJson(param)));
        return array;
    }

    private static com.google.gson.JsonArray strings(List<String> values) {
        var array = new com.google.gson.JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static JsonObject paramJson(ParameterSchema param) {
        JsonObject object = new JsonObject();
        object.addProperty("id", param.id());
        object.addProperty("type", param.type().name().toLowerCase(java.util.Locale.ROOT));
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
            param.nested().forEach(child -> nested.add(paramJson(child)));
            object.add("nested", nested);
        }
        if (param.uiHint() != null) {
            object.addProperty("uiHint", param.uiHint());
        }
        object.addProperty("label", param.editor().label());
        object.addProperty("section", param.editor().section());
        object.addProperty("order", param.editor().order());
        object.addProperty("advanced", param.editor().advanced());
        if (!param.editor().referenceKind().isBlank()) {
            object.addProperty("referenceKind", param.editor().referenceKind());
        }
        if (!param.editor().example().isBlank()) {
            object.addProperty("example", param.editor().example());
        }
        // Module metadata (issue #152): additive; older panels ignore it and
        // keep the section layout, newer panels hide nothing either way.
        if (param.editor().module()) {
            object.addProperty("module", true);
        }
        if (!param.editor().category().isBlank()) {
            object.addProperty("category", param.editor().category());
        }
        if (!param.editor().icon().isBlank()) {
            object.addProperty("icon", param.editor().icon());
        }
        if (!param.editor().summary().isBlank()) {
            object.addProperty("summary", param.editor().summary());
        }
        // Smart-input metadata (issue #153): labelled choices and number range/unit.
        if (!param.editor().options().isEmpty()) {
            var options = new com.google.gson.JsonArray();
            for (var option : param.editor().options()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("value", option.value());
                entry.addProperty("label", option.label());
                if (!option.description().isBlank()) {
                    entry.addProperty("description", option.description());
                }
                if (!option.icon().isBlank()) {
                    entry.addProperty("icon", option.icon());
                }
                options.add(entry);
            }
            object.add("options", options);
        }
        if (param.editor().min() != null) {
            object.addProperty("min", param.editor().min());
        }
        if (param.editor().max() != null) {
            object.addProperty("max", param.editor().max());
        }
        if (!param.editor().unit().isBlank()) {
            object.addProperty("unit", param.editor().unit());
        }
        return object;
    }

    /** Parses a wire {@code edits} array: set edits carry a value, removals {@code remove: true}. */
    /**
     * The optional {@code assets} of a studio save: {@code {path, data}} entries with
     * base64 bytes, the same shape {@code assets.put} takes. Absent means none; the
     * path rules and the size caps are the core transaction's, not the wire's.
     */
    private static List<AuthoringApi.ModelAsset> parseAssets(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("'assets' must be an array");
        }
        List<AuthoringApi.ModelAsset> assets = new java.util.ArrayList<>();
        for (var entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                throw new IllegalArgumentException("'assets' entries must be objects with path and data");
            }
            JsonObject asset = entry.getAsJsonObject();
            byte[] bytes = java.util.Base64.getDecoder().decode(requiredString(asset, "data"));
            assets.add(new AuthoringApi.ModelAsset(requiredString(asset, "path"), bytes));
        }
        return assets;
    }

    /** Registry entries of the {@code gui-components} registry, described like mechanics. */
    private static com.google.gson.JsonArray guiComponentsJson(List<RegistryEntry<?>> entries) {
        var array = new com.google.gson.JsonArray();
        for (RegistryEntry<?> entry : entries) {
            if (!(entry.entry() instanceof GuiComponent component)) {
                continue;
            }
            JsonObject json = new JsonObject();
            json.addProperty("id", entry.id().toString());
            describeInto(json, entry.description());
            json.addProperty("target", component.target().name().toLowerCase(java.util.Locale.ROOT));
            json.addProperty("registeredBy", entry.registeredBy());
            array.add(json);
        }
        return array;
    }

    private static List<AuthoringApi.FieldEdit> parseEdits(com.google.gson.JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException("'edits' must be an array");
        }
        if (element.getAsJsonArray().size() > 256) {
            throw new IllegalArgumentException("too many edits in one save");
        }
        List<AuthoringApi.FieldEdit> edits = new java.util.ArrayList<>();
        for (var entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                throw new IllegalArgumentException("'edits' entries must be objects");
            }
            JsonObject edit = entry.getAsJsonObject();
            var remove = edit.get("remove");
            if (remove != null && remove.isJsonPrimitive() && remove.getAsJsonPrimitive().isBoolean()
                    && remove.getAsBoolean()) {
                if (edit.has("value")) {
                    throw new IllegalArgumentException("a removal edit carries no 'value'");
                }
                edits.add(AuthoringApi.FieldEdit.removal(stringList(edit.get("field"))));
                continue;
            }
            edits.add(new AuthoringApi.FieldEdit(
                    stringList(edit.get("field")), nodeValue(edit.get("value"))));
        }
        return edits;
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

    /**
     * Studio saves carry structured values too (recipe ingredients, furniture
     * hitboxes): JSON maps and lists become ordered Java maps and lists, which
     * the lossless layer renders as block YAML. Depth is bounded so a hostile
     * client cannot blow the stack.
     */
    private static Object nodeValue(com.google.gson.JsonElement element) {
        return nodeValue(element, 0);
    }

    private static Object nodeValue(com.google.gson.JsonElement element, int depth) {
        if (depth > 8) {
            throw new IllegalArgumentException("value nests too deeply");
        }
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("'value' is required");
        }
        if (element.isJsonArray()) {
            List<Object> items = new java.util.ArrayList<>();
            for (var item : element.getAsJsonArray()) {
                items.add(nodeValue(item, depth + 1));
            }
            return items;
        }
        if (element.isJsonObject()) {
            var map = new java.util.LinkedHashMap<String, Object>();
            for (var entry : element.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), nodeValue(entry.getValue(), depth + 1));
            }
            return map;
        }
        return primitiveOf(element.getAsJsonPrimitive());
    }

    // ------------------------------------------------------------------

    /** Plain maps/lists/scalars (the api's view) → JSON (the wire shape). */
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

    /**
     * Renders a failed api call as the wire error it always was: the first
     * diagnostic's code and message, the op's target, and {@code hintOverride}
     * when the op historically carried its own hint.
     */
    private static Wire.Response error(
            Wire.Request request, List<Diagnostic> diagnostics,
            String target, String hintOverride) {
        Diagnostic diagnostic = diagnostics.isEmpty()
                ? null : diagnostics.get(0);
        String code = diagnostic == null ? "MTR-0011" : diagnostic.code().code();
        String message = diagnostic == null ? "request failed" : diagnostic.message();
        String hint = hintOverride != null ? hintOverride
                : diagnostic == null ? null : diagnostic.hint();
        return Wire.Response.failure(request, new Wire.Error(code, message, target, hint));
    }

    /** The op's historical hint, applied when the api diagnostic carries a different one. */
    private static String hintFor(List<Diagnostic> diagnostics, String fallback) {
        if (diagnostics.isEmpty()) {
            return fallback;
        }
        String hint = diagnostics.get(0).hint();
        return hint != null ? hint : fallback;
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

    private static int optionalInteger(JsonObject object, String field, int fallback) {
        var value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("'" + field + "' must be an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("'" + field + "' must be an integer");
        }
    }
}
