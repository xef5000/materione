package dev.materione.web.rpc.ops;

import com.google.gson.JsonObject;
import dev.materione.api.admin.AdminApi;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.pipeline.PipelineApi;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The control-center surface (web.md §4.2 "pipeline" + "admin" groups):
 * manual reloads, the audit trail, and plugin settings editing. Settings are
 * deliberately NOT documents — they live outside the packs root, have no
 * revision journal, and take effect on restart — so {@link AdminApi#putSettings}
 * guards them with an expected-content-hash check and a parse validation that
 * runs BEFORE any bytes land on disk; this layer renders the same wire errors
 * as always.
 */
public final class AdminOps {

    private AdminOps() {}

    /**
     * Registers the ops. {@code audit} is the manual-reload audit seam (the
     * api owns every other audit line; a user-initiated reload is an op-level
     * event and records here, exactly as the inline implementation did).
     */
    public static void register(
            RpcDispatcher dispatcher,
            AdminApi admin,
            PipelineApi pipeline,
            Function<String, ActorContext> actorFor,
            BiConsumer<String, String> audit) {
        dispatcher.register("pipeline.reload", request -> {
            try {
                ReloadScope scope = scopeOf(request.payload());
                // Fire-and-forget like every save: the outcome reaches editors
                // as push.reload. Blocking this dispatch thread on the
                // pipeline would stall the socket the reply rides on.
                pipeline.reload(actorFor.apply(actorOf(request)),
                        PipelineApi.ReloadRequest.manual(
                                scope, PipelineApi.DeliveryMode.CANDIDATE_PREVIEW));
                audit.accept(actorOf(request), label(scope));
                JsonObject payload = new JsonObject();
                payload.addProperty("queued", true);
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("admin.audit.recent", request -> {
            var limitElement = request.payload().get("limit");
            int limit = limitElement != null && limitElement.isJsonPrimitive()
                    ? limitElement.getAsInt() : 100;
            JsonObject payload = new JsonObject();
            var array = new com.google.gson.JsonArray();
            for (AdminApi.AuditEntry entry : admin.recentAudit(limit)) {
                JsonObject item = new JsonObject();
                item.addProperty("ts", entry.timestamp());
                item.addProperty("actor", entry.actor());
                item.addProperty("op", entry.operation());
                if (entry.target() != null) {
                    item.addProperty("target", entry.target());
                }
                array.add(item);
            }
            payload.add("entries", array);
            return Wire.Response.success(request, payload);
        });

        dispatcher.register("admin.settings.get", request -> {
            try {
                var result = admin.getSettings(actorFor.apply(actorOf(request)));
                if (!result.ok()) {
                    return error(request, result.diagnostics(), null, null);
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("raw", result.value().content());
                payload.addProperty("sha256", result.value().sha256());
                payload.add("parsed", jsonOf(result.value().parsed()));
                return Wire.Response.success(request, payload);
            } catch (Exception e) {
                return failure(request, e);
            }
        });

        dispatcher.register("admin.settings.put", request -> {
            JsonObject body = request.payload();
            try {
                String content = requiredString(body, "content");
                String expectedSha = requiredString(body, "sha256");
                var result = admin.putSettings(
                        actorFor.apply(actorOf(request)), content, expectedSha);
                if (!result.ok()) {
                    // Validation findings fold into one MTR-0007 message; a
                    // lost hash race stays the MTR-0034 it always was.
                    boolean conflict = result.diagnostics().stream()
                            .anyMatch(d -> "MTR-0034".equals(d.code().code()));
                    if (conflict) {
                        return error(request, result.diagnostics(), "settings.yml",
                                "re-read settings and re-apply your change");
                    }
                    var joined = new StringBuilder();
                    for (Diagnostic problem : result.diagnostics()) {
                        if (joined.length() > 0) {
                            joined.append("; ");
                        }
                        joined.append(problem.message());
                        if (problem.hint() != null) {
                            joined.append(" (").append(problem.hint()).append(')');
                        }
                    }
                    return Wire.Response.failure(request, new Wire.Error(
                            "MTR-0007", joined.toString(), "settings.yml",
                            "fix the reported problem and retry"));
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("bytes", content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            } catch (Exception e) {
                return failure(request, e);
            }
        });
    }

    // ------------------------------------------------------------------

    private static ReloadScope scopeOf(JsonObject payload) {
        String scope = requiredString(payload, "scope");
        return switch (scope) {
            case "everything" -> new ReloadScope.Everything();
            case "one-pack" -> new ReloadScope.OnePack(requiredString(payload, "pack"));
            default -> throw new IllegalArgumentException(
                    "scope must be 'everything' or 'one-pack': " + scope);
        };
    }

    private static String label(ReloadScope scope) {
        if (scope instanceof ReloadScope.OnePack pack) {
            return "one-pack:" + pack.packId();
        }
        return "everything";
    }

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

    private static Wire.Response error(
            Wire.Request request, List<Diagnostic> diagnostics, String target, String hint) {
        Diagnostic diagnostic = diagnostics.isEmpty() ? null : diagnostics.get(0);
        return Wire.Response.failure(request, new Wire.Error(
                diagnostic == null ? "MTR-0011" : diagnostic.code().code(),
                diagnostic == null ? "request failed" : diagnostic.message(),
                target,
                hint != null ? hint : diagnostic == null ? null : diagnostic.hint()));
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
