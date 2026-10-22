package dev.materione.web.rpc.ops;

import com.google.gson.JsonObject;
import dev.materione.core.document.AuditLog;
import dev.materione.web.ReloadTrigger;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The asset surface (web.md §4.2 "assets" group): list, upload, fetch and
 * delete pack-binary assets (textures, models, sounds) under
 * {@code content/packs/<pack>/assets/}. Binary rides base64-encoded INSIDE
 * the sealed channel frames — already end-to-end encrypted and signed — so
 * direct mode needs no second envelope; the §4.3 manifest+chunk scheme is a
 * RELAY-transport concern (resumability across the public store) and lands
 * with M5's cloud path. A per-asset size cap keeps WS frames bounded.
 */
public final class AssetOps {

    /** Per-asset cap for textures/models: they are KBs to low-MBs at most. */
    public static final int MAX_ASSET_BYTES = 2 * 1024 * 1024;

    /**
     * Audio rides the same sealed frames but is bulkier — a song-length
     * .ogg at a sensible bitrate needs more headroom (M4). The wire frame
     * budget is the real ceiling; larger sources wait for M5's chunk flow.
     */
    public static final int MAX_AUDIO_BYTES = 8 * 1024 * 1024;

    private static final Set<String> TEXT_TYPES = Set.of("png", "jpg", "jpeg", "json", "ogg", "oga", "mcmeta");
    private static final Set<String> AUDIO_TYPES = Set.of("ogg", "oga");

    private static int capFor(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot + 1);
        return AUDIO_TYPES.contains(extension) ? MAX_AUDIO_BYTES : MAX_ASSET_BYTES;
    }

    private AssetOps() {}

    public static void register(
            RpcDispatcher dispatcher,
            Path packsRoot,
            AuditLog audit,
            ReloadTrigger reload) {
        dispatcher.register("assets.list", request -> {
            String pack = requiredString(request.payload(), "pack");
            Path assetsDir = packsRoot.resolve(pack).resolve("assets");
            JsonObject payload = new JsonObject();
            var array = new com.google.gson.JsonArray();
            if (Files.isDirectory(assetsDir)) {
                try (Stream<Path> stream = Files.walk(assetsDir)) {
                    stream.filter(Files::isRegularFile).sorted().forEach(file -> {
                        String relative = packsRoot.resolve(pack).relativize(file)
                                .toString().replace('\\', '/');
                        entry(array, relative, file);
                    });
                } catch (IOException e) {
                    return failure(request, e);
                }
            }
            payload.add("assets", array);
            return Wire.Response.success(request, payload);
        });

        dispatcher.register("assets.put", request -> {
            JsonObject body = request.payload();
            try {
                String pack = requiredString(body, "pack");
                String path = safeAssetPath(body.get("path").getAsString());
                byte[] bytes = Base64.getDecoder().decode(requiredString(body, "data"));
                int cap = capFor(path);
                if (bytes.length > cap) {
                    throw new IllegalArgumentException(
                            "asset exceeds the " + (cap / 1024) + " KiB limit");
                }
                Path file = packsRoot.resolve(pack).resolve(path).normalize();
                if (!file.startsWith(packsRoot.resolve(pack))) {
                    throw new IllegalArgumentException("asset path escapes its pack: " + path);
                }
                Files.createDirectories(file.getParent());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp-" + System.nanoTime());
                Files.write(temp, bytes);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                audit.record(actorOf(request), "assets.put", pack + "/" + path);
                queueReload(reload, pack);
                JsonObject payload = new JsonObject();
                payload.addProperty("bytes", bytes.length);
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
                Path file = packsRoot.resolve(pack)
                        .resolve(readableAssetPath(request.payload().get("path").getAsString()))
                        .normalize();
                if (!file.startsWith(packsRoot.resolve(pack)) || !Files.isRegularFile(file)) {
                    return Wire.Response.failure(request,
                            new Wire.Error("MTR-0009", "no such asset", null, null));
                }
                JsonObject payload = new JsonObject();
                payload.addProperty(
                        "data",
                        Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
                return Wire.Response.success(request, payload);
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
                String from = safeAssetPath(body.get("from").getAsString());
                String to = safeAssetPath(body.get("to").getAsString());
                if (from.equals(to)) {
                    throw new IllegalArgumentException("move source and target are the same");
                }
                Path source = packsRoot.resolve(pack).resolve(from).normalize();
                Path target = packsRoot.resolve(pack).resolve(to).normalize();
                if (!source.startsWith(packsRoot.resolve(pack))
                        || !target.startsWith(packsRoot.resolve(pack))) {
                    throw new IllegalArgumentException("asset path escapes its pack");
                }
                if (!Files.isRegularFile(source)) {
                    return Wire.Response.failure(request,
                            new Wire.Error("MTR-0009", "no such asset: " + from, null, null));
                }
                if (Files.exists(target)) {
                    throw new IllegalStateException("target already exists: " + to);
                }
                Files.createDirectories(target.getParent());
                try {
                    Files.move(source, target);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                audit.record(actorOf(request), "assets.move", pack + "/" + from + " -> " + to);
                queueReload(reload, pack);
                return Wire.Response.success(request, new JsonObject());
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
                Path file = packsRoot.resolve(pack)
                        .resolve(safeAssetPath(request.payload().get("path").getAsString()))
                        .normalize();
                if (!file.startsWith(packsRoot.resolve(pack))) {
                    throw new IllegalArgumentException("asset path escapes its pack");
                }
                boolean existed = Files.deleteIfExists(file);
                audit.record(actorOf(request), "assets.delete", pack + "/" + request.payload()
                        .get("path").getAsString());
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

    /** Assets live under {@code assets/} with conservative name rules. */
    private static String safeAssetPath(String path) {
        if (path == null || !path.matches("assets/[A-Za-z0-9_./ -]+")
                || path.contains("..")) {
            throw new IllegalArgumentException(
                    "asset paths must start with assets/ and stay inside it: " + path);
        }
        return path.replace('\\', '/');
    }

    /**
     * Reads may also reach the model renders the Blockbench workbench caches
     * under {@code .materione/thumbnails/}, so a studio can show a model
     * without booting Blockbench. Writes deliberately cannot: authoring
     * metadata is written by the transaction that produces it
     * ({@code authoring.saveModel}), never by a free-form asset upload.
     */
    private static String readableAssetPath(String path) {
        if (path != null
                && path.matches("\\.materione/thumbnails/[A-Za-z0-9_./-]+\\.png")
                && !path.contains("..")) {
            return path;
        }
        return safeAssetPath(path);
    }

    private static void entry(com.google.gson.JsonArray array, String relative, Path file) {
        JsonObject object = new JsonObject();
        object.addProperty("path", relative);
        try {
            object.addProperty("bytes", Files.size(file));
        } catch (IOException e) {
            object.addProperty("bytes", -1);
        }
        String lower = relative.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        object.addProperty("kind", dot < 0 ? "bin" : lower.substring(dot + 1));
        array.add(object);
    }

    private static void queueReload(ReloadTrigger reload, String pack) {
        // Whole-pack scope for asset changes: texture edits can affect any
        // referencing item, and the fingerprint cache makes no-op cheap.
        reload.reloadScopeForPreview(
                new dev.materione.api.pipeline.ReloadScope.OnePack(pack),
                dev.materione.web.rpc.RpcDispatcher.currentSessionId())
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
