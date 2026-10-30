package dev.materione.web.rpc.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.LogicAuthoringApi;
import dev.materione.api.logic.LogicAuthoringApi.FlowView;
import dev.materione.api.logic.LogicAuthoringApi.InstantiateRequest;
import dev.materione.api.logic.LogicAuthoringApi.LogicDraft;
import dev.materione.api.logic.LogicAuthoringApi.LogicEditRequest;
import dev.materione.api.logic.LogicAuthoringApi.LogicView;
import dev.materione.api.logic.LogicAuthoringApi.NodePosition;
import dev.materione.api.logic.LogicAuthoringApi.RuleView;
import dev.materione.api.logic.LogicAuthoringApi.StepView;
import dev.materione.api.logic.LogicEdit;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The logic RPC ops (logic.md §8): {@code logic.vocabulary}, {@code logic.get}, {@code logic.edit},
 * {@code logic.validate} and {@code logic.instantiate}. Each handler is a pure JSON translator over
 * {@link LogicAuthoringApi} (R9): it reads the request, makes one API call and writes the reply. Every
 * op is gated on the {@code logic} session capability ({@code fixtures/protocol/editor-op-gates.json}).
 */
public final class LogicAuthoringOps {

    private LogicAuthoringOps() {}

    /**
     * Registers the five ops.
     *
     * @param logic the logic surface of {@code AuthoringApi.logic()}
     * @param actorFor maps an op's audit attribution to the acting {@link ActorContext}
     * @param vocabulary the behavior vocabulary, read per call (built-in and addon alike)
     */
    public static void register(RpcDispatcher dispatcher, LogicAuthoringApi logic,
            Function<String, ActorContext> actorFor, Supplier<dev.materione.api.registry.BehaviorVocabulary> vocabulary) {
        dispatcher.register("logic.vocabulary", request -> {
            try {
                return Wire.Response.success(request, EditorContentOps.vocabularyJson(vocabulary.get()));
            } catch (Exception e) {
                return EditorContentOps.failure(request, e);
            }
        });

        dispatcher.register("logic.get", request -> {
            JsonObject body = request.payload();
            try {
                ApiResult<LogicView> result = logic.get(
                        EditorContentOps.requiredString(body, "pack"), EditorContentOps.requiredString(body, "path"));
                if (!result.ok()) {
                    return EditorContentOps.error(request, result.diagnostics(), EditorContentOps.safePath(body), null);
                }
                return Wire.Response.success(request, viewJson(result.value(), List.of()));
            } catch (IllegalArgumentException e) {
                return EditorContentOps.badRequest(request, e.getMessage());
            } catch (Exception e) {
                return EditorContentOps.failure(request, e);
            }
        });

        dispatcher.register("logic.edit", request -> {
            JsonObject body = request.payload();
            try {
                var edits = new ArrayList<LogicEdit>();
                for (JsonElement entry : arrayOf(body, "edits")) {
                    edits.add(editOf(objectOf(entry, "an edit")));
                }
                ApiResult<LogicView> result = logic.edit(
                        actorFor.apply(EditorContentOps.actorOf(request)), new LogicEditRequest(
                        EditorContentOps.requiredString(body, "pack"),
                        EditorContentOps.requiredString(body, "path"),
                        requiredInteger(body, "rev"),
                        edits));
                if (!result.ok()) {
                    // a refused edit names the step it refuses, so the canvas can point at the node
                    String target = stepPathOf(result.diagnostics());
                    return EditorContentOps.error(request, result.diagnostics(),
                            target != null ? target : EditorContentOps.safePath(body),
                            EditorContentOps.hintFor(result.diagnostics(), "re-read the logic and re-apply your change"));
                }
                return Wire.Response.success(request, viewJson(result.value(), result.diagnostics()));
            } catch (IllegalArgumentException e) {
                return EditorContentOps.badRequest(request, e.getMessage());
            } catch (Exception e) {
                return EditorContentOps.failure(request, e);
            }
        });

        dispatcher.register("logic.validate", request -> {
            JsonObject body = request.payload();
            try {
                ApiResult<List<Diagnostic>> result = logic.validate(new LogicDraft(
                        EditorContentOps.requiredString(body, "pack"),
                        EditorContentOps.optionalString(body, "resource"),
                        listOf(body, "behavior"),
                        fieldsOf(body.get("flows")),
                        EditorContentOps.optionalString(body, "trigger")));
                if (!result.ok()) {
                    return EditorContentOps.error(request, result.diagnostics(), null, null);
                }
                JsonObject payload = new JsonObject();
                payload.add("diagnostics", GuiAuthoringOps.diagnosticsJson(result.value()));
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return EditorContentOps.badRequest(request, e.getMessage());
            } catch (Exception e) {
                return EditorContentOps.failure(request, e);
            }
        });

        dispatcher.register("logic.instantiate", request -> {
            JsonObject body = request.payload();
            try {
                ApiResult<List<Map<String, Object>>> result = logic.instantiate(new InstantiateRequest(
                        EditorContentOps.requiredString(body, "recipe"), fieldsOf(body.get("params"))));
                if (!result.ok()) {
                    return EditorContentOps.error(request, result.diagnostics(), null, null);
                }
                JsonObject payload = new JsonObject();
                payload.add("steps", EditorContentOps.jsonOf(result.value()));
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return EditorContentOps.badRequest(request, e.getMessage());
            } catch (Exception e) {
                return EditorContentOps.failure(request, e);
            }
        });
    }

    // ------------------------------------------------------------------
    // requests

    /**
     * An edit's wire shape: {@code {op, ...}}, with the op's own fields (see {@link LogicEdit}).
     * Paths are the step-path strings the API describes.
     */
    static LogicEdit editOf(JsonObject edit) {
        String op = EditorContentOps.requiredString(edit, "op");
        return switch (op) {
            case "addRule" -> new LogicEdit.AddRule(requiredInteger(edit, "index"), fieldsOf(edit.get("rule")));
            case "removeRules" -> new LogicEdit.RemoveRules(integersOf(edit.get("indices")));
            case "reorderRules" -> new LogicEdit.ReorderRules(integersOf(edit.get("order")));
            case "patchRule" -> new LogicEdit.PatchRule(
                    requiredInteger(edit, "index"), fieldsOf(edit.get("set")), stringsOf(edit.get("unset")));
            case "insertStep" -> new LogicEdit.InsertStep(
                    EditorContentOps.requiredString(edit, "path"), stepOf(edit.get("step")));
            case "patchStep" -> new LogicEdit.PatchStep(
                    EditorContentOps.requiredString(edit, "path"), fieldsOf(edit.get("set")), stringsOf(edit.get("unset")));
            case "moveStep" -> new LogicEdit.MoveStep(
                    EditorContentOps.requiredString(edit, "from"), EditorContentOps.requiredString(edit, "to"));
            case "removeStep" -> new LogicEdit.RemoveStep(EditorContentOps.requiredString(edit, "path"));
            case "setNodePosition" -> new LogicEdit.SetNodePosition(
                    EditorContentOps.requiredString(edit, "path"), numberOrNull(edit, "x"), numberOrNull(edit, "y"));
            case "addFlow" -> new LogicEdit.AddFlow(
                    EditorContentOps.requiredString(edit, "id"), listOf(edit, "steps"));
            case "removeFlow" -> new LogicEdit.RemoveFlow(EditorContentOps.requiredString(edit, "id"));
            case "renameFlow" -> new LogicEdit.RenameFlow(
                    EditorContentOps.requiredString(edit, "from"), EditorContentOps.requiredString(edit, "to"));
            default -> throw new IllegalArgumentException("unknown logic edit '" + op + "'");
        };
    }

    /** The step path the first diagnostic names ({@code behavior.0.do.1}), or null when it names none. */
    private static String stepPathOf(List<Diagnostic> diagnostics) {
        if (diagnostics.isEmpty() || diagnostics.get(0).target() == null) {
            return null;
        }
        return String.join(".", diagnostics.get(0).target().field());
    }

    /** A step as the API takes one: a mapping, or a bare name. */
    private static Object stepOf(JsonElement element) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        return EditorContentOps.nodeValue(element);
    }

    // ------------------------------------------------------------------
    // replies

    /** A resource's logic as the ops reply it: the typed view, plus the compiler's warnings. */
    static JsonObject viewJson(LogicView view, List<Diagnostic> warnings) {
        JsonObject payload = new JsonObject();
        payload.addProperty("pack", view.pack());
        payload.addProperty("path", view.path());
        payload.addProperty("id", view.resourceId());
        payload.addProperty("rev", view.revision());
        var rules = new JsonArray();
        for (RuleView rule : view.rules()) {
            JsonObject json = new JsonObject();
            json.addProperty("index", rule.index());
            json.addProperty("path", rule.path());
            if (rule.on() != null) {
                json.addProperty("on", rule.on());
            }
            json.add("options", EditorContentOps.jsonOf(rule.options()));
            json.add("condition", EditorContentOps.jsonOf(rule.condition()));
            addNode(json, rule.node());
            json.add("steps", stepsJson(rule.steps()));
            rules.add(json);
        }
        payload.add("rules", rules);
        var flows = new JsonArray();
        for (FlowView flow : view.flows()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", flow.id());
            json.add("steps", stepsJson(flow.steps()));
            flows.add(json);
        }
        payload.add("flows", flows);
        payload.add("warnings", GuiAuthoringOps.diagnosticsJson(warnings));
        return payload;
    }

    private static JsonArray stepsJson(List<StepView> steps) {
        var array = new JsonArray();
        for (StepView step : steps) {
            JsonObject json = new JsonObject();
            json.addProperty("path", step.path());
            json.addProperty("kind", step.kind());
            json.add("value", EditorContentOps.jsonOf(step.value()));
            json.add("options", EditorContentOps.jsonOf(step.options()));
            addNode(json, step.node());
            var branches = new JsonObject();
            step.branches().forEach((key, nested) -> branches.add(key, stepsJson(nested)));
            json.add("branches", branches);
            array.add(json);
        }
        return array;
    }

    private static void addNode(JsonObject json, NodePosition node) {
        if (node == null) {
            json.add("node", JsonNull.INSTANCE);
            return;
        }
        JsonObject position = new JsonObject();
        position.addProperty("x", node.x());
        position.addProperty("y", node.y());
        json.add("node", position);
    }

    // ------------------------------------------------------------------
    // small readers

    private static int requiredInteger(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("'" + field + "' must be an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("'" + field + "' must be an integer");
        }
    }

    private static Double numberOrNull(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("'" + field + "' must be a number or null");
        }
        return value.getAsDouble();
    }

    private static JsonObject objectOf(JsonElement element, String what) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(what + " must be an object");
        }
        return element.getAsJsonObject();
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

    /** A JSON array of steps or other values as the API takes it, in order. */
    private static List<Object> listOf(JsonObject object, String field) {
        var out = new ArrayList<Object>();
        for (JsonElement entry : arrayOf(object, field)) {
            out.add(stepOf(entry));
        }
        return out;
    }

    private static List<Integer> integersOf(JsonElement element) {
        var out = new ArrayList<Integer>();
        if (element == null || element.isJsonNull()) {
            return out;
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("indexes must be an array of integers");
        }
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("indexes must be integers");
            }
            out.add(entry.getAsBigDecimal().intValueExact());
        }
        return out;
    }

    /** A JSON object of fields as the API takes them: a JSON null stays null (it removes the field). */
    private static Map<String, Object> fieldsOf(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Map.of();
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("fields must be an object");
        }
        var fields = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            fields.put(entry.getKey(), entry.getValue() == null || entry.getValue().isJsonNull()
                    ? null : EditorContentOps.nodeValue(entry.getValue()));
        }
        return fields;
    }

    private static List<String> stringsOf(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        return EditorContentOps.stringList(element);
    }
}
