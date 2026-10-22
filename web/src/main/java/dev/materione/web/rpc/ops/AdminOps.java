package dev.materione.web.rpc.ops;

import com.google.gson.JsonObject;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.core.document.AuditLog;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.web.ReloadTrigger;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The control-center surface (web.md §4.2 "pipeline" + "admin" groups):
 * manual reloads, the audit trail, and plugin settings editing. Settings are
 * deliberately NOT documents — they live outside the packs root, have no
 * revision journal, and take effect on restart — so this registrar guards
 * them with an expected-content-hash check and a parse validation that runs
 * BEFORE any bytes land on disk.
 */
public final class AdminOps {

    /** Serializes settings read-modify-write across concurrent editors. */
    private static final Object SETTINGS_LOCK = new Object();

    private AdminOps() {}

    public static void register(
            RpcDispatcher dispatcher,
            AuditLog audit,
            ReloadTrigger reload,
            Path settingsFile,
            java.util.function.Function<String, List<Diagnostic>> settingsValidator) {
        dispatcher.register("pipeline.reload", request -> {
            try {
                ReloadScope scope = scopeOf(request.payload());
                // Fire-and-forget like every save: the outcome reaches editors
                // as push.reload. Blocking this dispatch thread on the
                // pipeline would stall the socket the reply rides on.
                reload.reloadScope(scope);
                audit.record(actorOf(request), "pipeline.reload", label(scope));
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
            for (AuditLog.Entry entry : audit.recent(limit)) {
                JsonObject item = new JsonObject();
                item.addProperty("ts", entry.ts());
                item.addProperty("actor", entry.actor());
                item.addProperty("op", entry.op());
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
                if (!Files.isRegularFile(settingsFile)) {
                    return Wire.Response.failure(request,
                            new Wire.Error("MTR-0009", "settings file does not exist yet", null, null));
                }
                String raw = Files.readString(settingsFile, StandardCharsets.UTF_8);
                JsonObject payload = new JsonObject();
                payload.addProperty("raw", raw);
                payload.addProperty("sha256", dev.materione.core.document.Documents.sha256(raw));
                payload.add("parsed", parsedView(raw));
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
                List<Diagnostic> problems = settingsValidator.apply(content);
                if (!problems.isEmpty()) {
                    // The wire error carries one message — fold the validator's
                    // findings into it so the editor can show them inline.
                    var joined = new StringBuilder();
                    for (Diagnostic problem : problems) {
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
                synchronized (SETTINGS_LOCK) {
                    String current = Files.isRegularFile(settingsFile)
                            ? Files.readString(settingsFile, StandardCharsets.UTF_8) : "";
                    if (!dev.materione.core.document.Documents.sha256(current).equals(expectedSha)) {
                        return Wire.Response.failure(request, new Wire.Error(
                                "MTR-0034", "settings changed elsewhere since you loaded them",
                                "settings.yml", "re-read settings and re-apply your change"));
                    }
                    atomicWrite(settingsFile, content.getBytes(StandardCharsets.UTF_8));
                }
                audit.record(actorOf(request), "admin.settings.put", "settings.yml");
                JsonObject payload = new JsonObject();
                payload.addProperty("bytes", content.getBytes(StandardCharsets.UTF_8).length);
                return Wire.Response.success(request, payload);
            } catch (IOException e) {
                return failure(request, e);
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

    private static void atomicWrite(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp-" + System.nanoTime());
        Files.write(temp, bytes);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The data view for the settings form pre-fill. */
    @SuppressWarnings("unchecked")
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

    private static String requiredString(JsonObject object, String field) {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing field '" + field + "'");
        }
        return value.getAsString();
    }
}
