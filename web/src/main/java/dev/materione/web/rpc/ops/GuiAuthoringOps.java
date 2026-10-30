package dev.materione.web.rpc.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.gui.GuiAuthoringApi;
import dev.materione.api.gui.GuiAuthoringApi.AddLayer;
import dev.materione.api.gui.GuiAuthoringApi.CustomizeItemRequest;
import dev.materione.api.gui.GuiAuthoringApi.CustomizeItemResult;
import dev.materione.api.gui.GuiAuthoringApi.GuiDocumentView;
import dev.materione.api.gui.GuiAuthoringApi.GuiEdit;
import dev.materione.api.gui.GuiAuthoringApi.GuiEditRequest;
import dev.materione.api.gui.GuiAuthoringApi.OpenArg;
import dev.materione.api.gui.GuiAuthoringApi.OpenSpec;
import dev.materione.api.gui.GuiAuthoringApi.SetOpen;
import dev.materione.api.gui.GuiAuthoringApi.GuiRender;
import dev.materione.api.gui.GuiAuthoringApi.GuiRenderRequest;
import dev.materione.api.gui.GuiAuthoringApi.GuiSource;
import dev.materione.api.gui.GuiAuthoringApi.GuiValidateRequest;
import dev.materione.api.gui.GuiAuthoringApi.Layer;
import dev.materione.api.gui.GuiAuthoringApi.LayerRender;
import dev.materione.api.gui.GuiAuthoringApi.PatchLayer;
import dev.materione.api.gui.GuiAuthoringApi.RemoveLayers;
import dev.materione.api.gui.GuiAuthoringApi.ReorderLayers;
import dev.materione.api.gui.GuiAuthoringApi.SetScreen;
import dev.materione.api.gui.GuiAuthoringApi.Saved;
import dev.materione.api.gui.GuiAuthoringApi.Typed;
import dev.materione.api.gui.GuiAuthoringApi.Yaml;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The GUI builder's RPC ops (gui.md §7): {@code gui.get}, {@code gui.edit},
 * {@code gui.customizeItem}, {@code gui.render} and {@code gui.validate}. Each handler is a pure
 * JSON translator over {@link GuiAuthoringApi} (R9): it reads the request, makes one API call and
 * writes the reply. Every op is gated on the {@code gui} session capability
 * ({@code fixtures/protocol/editor-op-gates.json}).
 */
public final class GuiAuthoringOps {

    private GuiAuthoringOps() {}

    /**
     * Registers the five ops.
     *
     * @param gui the GUI surface of {@code AuthoringApi.gui()}
     * @param actorFor maps an op's audit attribution to the acting {@link ActorContext}
     */
    public static void register(RpcDispatcher dispatcher, GuiAuthoringApi gui,
            Function<String, ActorContext> actorFor) {
        dispatcher.register("gui.get", request -> {
            JsonObject body = request.payload();
            try {
                ApiResult<GuiDocumentView> result = gui.get(
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

        dispatcher.register("gui.edit", request -> {
            JsonObject body = request.payload();
            try {
                var edits = new ArrayList<GuiEdit>();
                for (JsonElement entry : arrayOf(body, "edits")) {
                    edits.add(editOf(objectOf(entry, "an edit")));
                }
                ApiResult<GuiDocumentView> result = gui.edit(
                        actorFor.apply(EditorContentOps.actorOf(request)), new GuiEditRequest(
                        EditorContentOps.requiredString(body, "pack"),
                        EditorContentOps.requiredString(body, "path"),
                        body.get("rev").getAsInt(),
                        edits,
                        EditorContentOps.parseAssets(body.get("assets"))));
                if (!result.ok()) {
                    return EditorContentOps.error(request, result.diagnostics(), EditorContentOps.safePath(body),
                            EditorContentOps.hintFor(result.diagnostics(), "re-read the GUI and re-apply your change"));
                }
                return Wire.Response.success(request, viewJson(result.value(), result.diagnostics()));
            } catch (IllegalArgumentException e) {
                return EditorContentOps.badRequest(request, e.getMessage());
            } catch (Exception e) {
                return EditorContentOps.failure(request, e);
            }
        });

        dispatcher.register("gui.customizeItem", request -> {
            JsonObject body = request.payload();
            try {
                ApiResult<CustomizeItemResult> result = gui.customizeItem(
                        actorFor.apply(EditorContentOps.actorOf(request)),
                        new CustomizeItemRequest(
                                EditorContentOps.requiredString(body, "pack"),
                                EditorContentOps.requiredString(body, "path"),
                                body.get("rev").getAsInt(),
                                EditorContentOps.requiredString(body, "layer")));
                if (!result.ok()) {
                    return EditorContentOps.error(request, result.diagnostics(), EditorContentOps.safePath(body), null);
                }
                CustomizeItemResult created = result.value();
                JsonObject payload = new JsonObject();
                payload.addProperty("id", created.itemId());
                payload.addProperty("path", created.itemPath());
                payload.addProperty("reference", created.reference());
                payload.addProperty("rev", created.revision());
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return EditorContentOps.badRequest(request, e.getMessage());
            } catch (Exception e) {
                return EditorContentOps.failure(request, e);
            }
        });

        dispatcher.register("gui.render", request -> {
            JsonObject body = request.payload();
            try {
                ApiResult<GuiRender> result = gui.render(new GuiRenderRequest(
                        sourceOf(body), EditorContentOps.parseAssets(body.get("assets"))));
                if (!result.ok()) {
                    return EditorContentOps.error(request, result.diagnostics(), null, null);
                }
                return Wire.Response.success(request, renderJson(result.value()));
            } catch (IllegalArgumentException e) {
                return EditorContentOps.badRequest(request, e.getMessage());
            } catch (Exception e) {
                return EditorContentOps.failure(request, e);
            }
        });

        dispatcher.register("gui.validate", request -> {
            JsonObject body = request.payload();
            try {
                ApiResult<List<Diagnostic>> result = gui.validate(new GuiValidateRequest(
                        sourceOf(body), EditorContentOps.parseAssets(body.get("assets"))));
                if (!result.ok()) {
                    return EditorContentOps.error(request, result.diagnostics(), null, null);
                }
                JsonObject payload = new JsonObject();
                payload.add("diagnostics", diagnosticsJson(result.value()));
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

    /** A GUI edit's wire shape: {@code {op: addLayer|patchLayer|removeLayers|reorderLayers|setScreen, ...}}. */
    private static GuiEdit editOf(JsonObject edit) {
        String op = EditorContentOps.requiredString(edit, "op");
        return switch (op) {
            case "addLayer" -> new AddLayer(
                    requiredInteger(edit, "index"),
                    EditorContentOps.requiredString(edit, "type"),
                    EditorContentOps.optionalString(edit, "id"),
                    fieldsOf(edit.get("params")));
            case "patchLayer" -> new PatchLayer(
                    EditorContentOps.requiredString(edit, "id"),
                    fieldsOf(edit.get("set")),
                    stringsOf(edit.get("unset")));
            case "removeLayers" -> new RemoveLayers(stringsOf(edit.get("ids")));
            case "reorderLayers" -> new ReorderLayers(stringsOf(edit.get("ids")));
            case "setScreen" -> new SetScreen(
                    EditorContentOps.optionalString(edit, "title"),
                    edit.has("rows") && !edit.get("rows").isJsonNull()
                            ? requiredInteger(edit, "rows") : null);
            case "setOpen" -> new SetOpen(openOf(edit.get("open")));
            default -> throw new IllegalArgumentException("unknown GUI edit '" + op + "'");
        };
    }

    /** The {@code open:} block as the wire carries it; null (or JSON null) removes it. */
    private static OpenSpec openOf(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject block = objectOf(element, "the open block");
        var args = new ArrayList<OpenArg>();
        for (JsonElement entry : arrayOf(block, "args")) {
            JsonObject arg = objectOf(entry, "an open argument");
            args.add(new OpenArg(
                    EditorContentOps.requiredString(arg, "name"),
                    EditorContentOps.requiredString(arg, "type"),
                    arg.has("optional") && arg.get("optional").getAsBoolean(),
                    arg.has("default") && !arg.get("default").isJsonNull()
                            ? EditorContentOps.nodeValue(arg.get("default")) : null,
                    stringsOf(arg.get("suggest"))));
        }
        var deny = new ArrayList<Object>();
        for (JsonElement step : arrayOf(block, "deny")) {
            deny.add(EditorContentOps.nodeValue(step));
        }
        return new OpenSpec(
                stringsOf(block.get("commands")),
                args,
                EditorContentOps.optionalString(block, "permission"),
                block.has("requirement") && !block.get("requirement").isJsonNull()
                        ? EditorContentOps.nodeValue(block.get("requirement")) : null,
                deny);
    }

    /** The {@code open:} block as the typed view writes it for {@code gui.get} and {@code gui.edit}. */
    private static JsonObject openJson(OpenSpec open) {
        JsonObject json = new JsonObject();
        json.add("commands", EditorContentOps.jsonOf(open.commands()));
        var args = new JsonArray();
        for (OpenArg arg : open.args()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", arg.name());
            entry.addProperty("type", arg.type());
            entry.addProperty("optional", arg.optional());
            if (arg.defaultValue() != null) {
                entry.add("default", EditorContentOps.jsonOf(arg.defaultValue()));
            }
            entry.add("suggest", EditorContentOps.jsonOf(arg.suggest()));
            args.add(entry);
        }
        json.add("args", args);
        if (open.permission() != null) {
            json.addProperty("permission", open.permission());
        }
        if (open.requirement() != null) {
            json.add("requirement", EditorContentOps.jsonOf(open.requirement()));
        }
        json.add("deny", EditorContentOps.jsonOf(open.deny()));
        return json;
    }

    /** A source: {@code {kind: saved, pack, path}}, {@code {kind: yaml, pack, text}} or {@code {kind: draft, document}}. */
    private static GuiSource sourceOf(JsonObject body) {
        if (!(body.get("source") instanceof JsonObject source)) {
            throw new IllegalArgumentException("'source' must be an object");
        }
        String kind = EditorContentOps.requiredString(source, "kind");
        return switch (kind) {
            case "saved" -> new Saved(
                    EditorContentOps.requiredString(source, "pack"), EditorContentOps.requiredString(source, "path"));
            case "yaml" -> new Yaml(
                    EditorContentOps.requiredString(source, "pack"), EditorContentOps.requiredString(source, "text"));
            case "draft" -> new Typed(documentOf(objectOf(source.get("document"), "the draft document")));
            default -> throw new IllegalArgumentException("unknown source kind '" + kind + "'");
        };
    }

    /** A typed draft in the shape {@code gui.get} returns. */
    private static GuiDocumentView documentOf(JsonObject document) {
        var layers = new ArrayList<Layer>();
        for (JsonElement entry : arrayOf(document, "layers")) {
            JsonObject layer = objectOf(entry, "a layer");
            layers.add(new Layer(
                    EditorContentOps.requiredString(layer, "id"),
                    EditorContentOps.requiredString(layer, "type"),
                    EditorContentOps.optionalString(layer, "name"),
                    !layer.has("visible") || layer.get("visible").getAsBoolean(),
                    layer.has("locked") && layer.get("locked").getAsBoolean(),
                    layer.has("opacity") && !layer.get("opacity").isJsonNull() ? requiredInteger(layer, "opacity") : null,
                    layer.has("parameters") ? fieldsOf(layer.get("parameters")) : Map.of()));
        }
        return new GuiDocumentView(
                EditorContentOps.requiredString(document, "pack"),
                EditorContentOps.optionalString(document, "path"),
                EditorContentOps.requiredString(document, "id"),
                EditorContentOps.optionalString(document, "title"),
                document.has("rows") && !document.get("rows").isJsonNull() ? requiredInteger(document, "rows") : null,
                0,
                layers,
                openOf(document.get("open")));
    }

    // ------------------------------------------------------------------
    // replies

    /** A GUI as {@code gui.get} and {@code gui.edit} reply it: the typed view, plus the edit's warnings. */
    private static JsonObject viewJson(GuiDocumentView view, List<Diagnostic> warnings) {
        JsonObject payload = new JsonObject();
        payload.addProperty("pack", view.pack());
        payload.addProperty("path", view.path());
        payload.addProperty("id", view.id());
        if (view.title() != null) {
            payload.addProperty("title", view.title());
        }
        if (view.rows() != null) {
            payload.addProperty("rows", view.rows());
        }
        payload.addProperty("rev", view.revision());
        var layers = new JsonArray();
        for (Layer layer : view.layers()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", layer.id());
            json.addProperty("type", layer.type());
            if (layer.name() != null) {
                json.addProperty("name", layer.name());
            }
            json.addProperty("visible", layer.visible());
            json.addProperty("locked", layer.locked());
            if (layer.opacity() != null) {
                json.addProperty("opacity", layer.opacity());
            }
            json.add("parameters", EditorContentOps.jsonOf(layer.parameters()));
            layers.add(json);
        }
        payload.add("layers", layers);
        if (view.open() != null) {
            payload.add("open", openJson(view.open()));
        }
        payload.add("warnings", diagnosticsJson(warnings));
        return payload;
    }

    private static JsonObject renderJson(GuiRender render) {
        JsonObject payload = new JsonObject();
        payload.addProperty("width", render.width());
        payload.addProperty("height", render.height());
        payload.addProperty("background", Base64.getEncoder().encodeToString(render.background()));
        var layers = new JsonArray();
        for (LayerRender layer : render.layers()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", layer.id());
            json.addProperty("target", layer.target() == null
                    ? null : layer.target().name().toLowerCase(Locale.ROOT));
            json.addProperty("rank", layer.compiledRank());
            json.addProperty("skipped", layer.skipped());
            json.addProperty("reason", layer.reason());
            layers.add(json);
        }
        payload.add("layers", layers);
        payload.add("diagnostics", diagnosticsJson(render.diagnostics()));
        return payload;
    }

    /** Diagnostics as the studio reads them: code, severity, message, the resource and field, and the hint. */
    static JsonArray diagnosticsJson(List<Diagnostic> diagnostics) {
        var array = new JsonArray();
        for (Diagnostic diagnostic : diagnostics) {
            JsonObject json = new JsonObject();
            json.addProperty("code", diagnostic.code().code());
            json.addProperty("severity", diagnostic.severity().name().toLowerCase(Locale.ROOT));
            json.addProperty("message", diagnostic.message());
            json.addProperty("resource", diagnostic.resource() == null ? null : diagnostic.resource().toString());
            if (diagnostic.target() != null) {
                json.add("field", EditorContentOps.jsonOf(diagnostic.target().field()));
            }
            json.addProperty("hint", diagnostic.hint());
            array.add(json);
        }
        return array;
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
            fields.put(entry.getKey(), entry.getValue() instanceof JsonNull || entry.getValue() == null
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
