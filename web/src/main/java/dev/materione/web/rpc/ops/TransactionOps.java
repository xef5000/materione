package dev.materione.web.rpc.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.authoring.TransactionOperation;
import dev.materione.api.authoring.TransactionRequest;
import dev.materione.api.authoring.TransactionResult;
import dev.materione.api.authoring.TransactionResult.OperationDiagnostic;
import dev.materione.api.authoring.TransactionResult.Stage;
import dev.materione.api.authoring.TransactionResult.TransactionDocument;
import dev.materione.api.gui.GuiAuthoringApi.GuiEdit;
import dev.materione.api.logic.LogicEdit;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The code-mode transaction op (codemode.md section 10): {@code authoring.transaction}. A pure JSON translator
 * over {@link AuthoringApi#transaction} (R9): it reads the operation list, makes one API call and writes the
 * report. The op is additive and gated on the {@code codemode} session capability
 * ({@code fixtures/protocol/editor-op-gates.json}), so an editor that does not know it is unaffected.
 *
 * <p>Unlike most ops, a refused list is a <em>success</em> frame: the per-operation diagnostics and the stage
 * report are the answer, and the first-diagnostic error frame would lose them. {@code accepted} says whether the
 * list would apply (dry run) or did. Only a malformed request is an error frame.
 */
public final class TransactionOps {

    private TransactionOps() {}

    /**
     * Registers {@code authoring.transaction}.
     *
     * @param actorFor maps an op's audit attribution to the acting {@link ActorContext}
     */
    public static void register(
            RpcDispatcher dispatcher, AuthoringApi authoring, Function<String, ActorContext> actorFor) {
        dispatcher.register("authoring.transaction", request -> {
            JsonObject body = request.payload();
            try {
                JsonElement dryRun = body.get("dryRun");
                if (dryRun == null || !dryRun.isJsonPrimitive() || !dryRun.getAsJsonPrimitive().isBoolean()) {
                    // no default: a request that forgets the flag must not turn into a write
                    throw new IllegalArgumentException("'dryRun' must be true or false");
                }
                TransactionRequest parsed = new TransactionRequest(
                        EditorContentOps.requiredString(body, "pack"), operationsOf(body), dryRun.getAsBoolean());
                ApiResult<TransactionResult> result =
                        authoring.transaction(actorFor.apply(EditorContentOps.actorOf(request)), parsed);
                if (result.value() == null) {
                    return EditorContentOps.error(request, result.diagnostics(), null, null);
                }
                return Wire.Response.success(request, resultJson(result.value()));
            } catch (IllegalArgumentException e) {
                return EditorContentOps.badRequest(request, e.getMessage());
            } catch (Exception e) {
                return EditorContentOps.failure(request, e);
            }
        });
    }

    // ------------------------------------------------------------------
    // request

    private static List<TransactionOperation> operationsOf(JsonObject body) {
        JsonElement element = body.get("operations");
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException("'operations' must be a non-empty array");
        }
        if (element.getAsJsonArray().size() > TransactionRequest.MAX_OPERATIONS) {
            throw new IllegalArgumentException(
                    "at most " + TransactionRequest.MAX_OPERATIONS + " operations per transaction");
        }
        List<TransactionOperation> out = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                throw new IllegalArgumentException("'operations' entries must be objects");
            }
            out.add(operationOf(entry.getAsJsonObject()));
        }
        return out;
    }

    /**
     * One operation's wire shape: {@code {kind, ...}} with the kind's own fields. {@code rev} is the revision the
     * author read, absent for a document the list itself creates.
     */
    static TransactionOperation operationOf(JsonObject op) {
        String kind = EditorContentOps.requiredString(op, "kind");
        Integer rev = op.has("rev") && !op.get("rev").isJsonNull()
                ? EditorContentOps.optionalInteger(op, "rev", -1) : null;
        return switch (kind) {
            case "create" -> new TransactionOperation.Create(
                    EditorContentOps.optionalString(op, "type"),
                    EditorContentOps.optionalString(op, "folder"),
                    EditorContentOps.optionalString(op, "id"),
                    EditorContentOps.optionalString(op, "name"),
                    editsOf(op));
            case "duplicate" -> new TransactionOperation.Duplicate(
                    EditorContentOps.requiredString(op, "fromPath"), rev,
                    EditorContentOps.optionalString(op, "folder"),
                    EditorContentOps.optionalString(op, "id"),
                    editsOf(op));
            case "setFields" -> new TransactionOperation.SetFields(
                    EditorContentOps.requiredString(op, "path"), rev, editsOf(op));
            case "move" -> new TransactionOperation.Move(
                    EditorContentOps.requiredString(op, "fromPath"), rev,
                    EditorContentOps.requiredString(op, "toPath"));
            case "delete" -> new TransactionOperation.Delete(EditorContentOps.requiredString(op, "path"), rev);
            case "detachTemplate" -> new TransactionOperation.DetachTemplate(
                    EditorContentOps.requiredString(op, "path"), rev, EditorContentOps.optionalString(op, "resource"));
            case "saveAsTemplate" -> new TransactionOperation.SaveAsTemplate(
                    EditorContentOps.requiredString(op, "fromPath"), rev,
                    EditorContentOps.optionalString(op, "resource"),
                    EditorContentOps.requiredString(op, "templateId"),
                    op.has("fields") && !op.get("fields").isJsonNull()
                            ? EditorContentOps.stringList(op.get("fields")) : List.of());
            case "logicEdit" -> {
                var edits = new ArrayList<LogicEdit>();
                for (JsonElement entry : arrayOf(op, "edits")) {
                    edits.add(LogicAuthoringOps.editOf(objectOf(entry, "a logic edit")));
                }
                yield new TransactionOperation.EditLogic(EditorContentOps.requiredString(op, "path"), rev, edits);
            }
            case "guiEdit" -> {
                var edits = new ArrayList<GuiEdit>();
                for (JsonElement entry : arrayOf(op, "edits")) {
                    edits.add(GuiAuthoringOps.editOf(objectOf(entry, "a GUI edit")));
                }
                yield new TransactionOperation.EditGui(EditorContentOps.requiredString(op, "path"), rev, edits);
            }
            default -> throw new IllegalArgumentException("unknown operation kind '" + kind + "'");
        };
    }

    private static List<AuthoringApi.FieldEdit> editsOf(JsonObject op) {
        JsonElement raw = op.get("edits");
        return raw == null || raw.isJsonNull() ? List.of() : EditorContentOps.parseEdits(raw);
    }

    private static JsonArray arrayOf(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return new JsonArray();
        }
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException("'" + field + "' must be an array");
        }
        return value.getAsJsonArray();
    }

    private static JsonObject objectOf(JsonElement element, String what) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(what + " must be an object");
        }
        return element.getAsJsonObject();
    }

    // ------------------------------------------------------------------
    // reply

    static JsonObject resultJson(TransactionResult result) {
        JsonObject payload = new JsonObject();
        payload.addProperty("accepted", result.accepted());
        payload.addProperty("applied", result.applied());
        payload.addProperty("dryRun", result.dryRun());
        payload.add("transactionId", result.transactionId() == null
                ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(result.transactionId()));
        var documents = new JsonArray();
        for (TransactionDocument document : result.documents()) {
            JsonObject json = new JsonObject();
            json.addProperty("path", document.path());
            json.addProperty("kind", document.kind().name().toLowerCase(Locale.ROOT));
            json.add("previousRevision", numberOrNull(document.previousRevision()));
            json.add("rev", numberOrNull(document.revision()));
            json.add("text", document.text() == null
                    ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(document.text()));
            if (document.movedFrom() != null) {
                json.addProperty("movedFrom", document.movedFrom());
            }
            var operations = new JsonArray();
            document.operations().forEach(operations::add);
            json.add("operations", operations);
            documents.add(json);
        }
        payload.add("documents", documents);
        var diagnostics = new JsonArray();
        for (OperationDiagnostic found : result.diagnostics()) {
            JsonObject json = GuiAuthoringOps.diagnosticsJson(List.of(found.diagnostic())).get(0).getAsJsonObject();
            json.addProperty("operation", found.operation());
            diagnostics.add(json);
        }
        payload.add("diagnostics", diagnostics);
        var stages = new JsonArray();
        for (Stage stage : result.stages()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", stage.id());
            json.addProperty("ran", stage.ran());
            json.addProperty("note", stage.note());
            stages.add(json);
        }
        payload.add("stages", stages);
        return payload;
    }

    private static JsonElement numberOrNull(Integer value) {
        return value == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(value);
    }
}
