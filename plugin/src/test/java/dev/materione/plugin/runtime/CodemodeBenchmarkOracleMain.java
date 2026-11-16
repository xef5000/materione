package dev.materione.plugin.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.materione.core.storage.StorageService;
import dev.materione.testing.golden.GoldenFiles;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Long-lived JSON-Lines oracle process for the code-mode benchmark (issues 385, 349).
 * Lives in {@code src/test/java} only so zero bytes enter the production plugin JAR.
 */
public final class CodemodeBenchmarkOracleMain {

    private static final String PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Path workspace;
    private RpcDispatcher dispatcher;

    public static void main(String[] args) throws IOException {
        PrintStream protocolOut = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        CodemodeBenchmarkOracleMain oracle = new CodemodeBenchmarkOracleMain();
        CodemodeBenchmarkOracleTest.registerShutdownHook(oracle);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject response;
                boolean shouldExit = false;
                try {
                    JsonObject req = JsonParser.parseString(line).getAsJsonObject();
                    String cmd = req.has("cmd") && !req.get("cmd").isJsonNull() ? req.get("cmd").getAsString() : "";
                    response = oracle.handle(req);
                    shouldExit = "close".equals(cmd);
                } catch (Exception e) {
                    response = errorResponse(null, "ORACLE-ERROR", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
                protocolOut.println(GSON.toJson(response));
                protocolOut.flush();
                if (shouldExit) {
                    break;
                }
            }
        } finally {
            oracle.cleanup();
        }
    }

    JsonObject handle(JsonObject req) throws IOException {
        JsonElement id = req.get("id");
        String cmd = req.has("cmd") && !req.get("cmd").isJsonNull() ? req.get("cmd").getAsString() : "";
        return switch (cmd) {
            case "ping" -> {
                JsonObject data = new JsonObject();
                data.addProperty("ready", true);
                yield okResponse(id, data);
            }
            case "resetWorld" -> resetWorld(id, req);
            case "rpc" -> dispatchRpc(id, req);
            case "close" -> {
                cleanup();
                JsonObject data = new JsonObject();
                data.addProperty("closed", true);
                yield okResponse(id, data);
            }
            default -> errorResponse(id, "ORACLE-UNKNOWN-CMD", "unknown command: " + cmd);
        };
    }

    private JsonObject resetWorld(JsonElement id, JsonObject req) throws IOException {
        cleanup();
        workspace = Files.createTempDirectory("materione-bench-oracle-");
        StorageService.StorageState storage = new StorageService().init(workspace);
        for (String fixturePack : EditorDemoSnapshotTest.PACKS) {
            EditorDemoSnapshotTest.copyTree(
                    GoldenFiles.fixturesRoot().resolve("packs").resolve(fixturePack),
                    storage.contentPacksDir().resolve(fixturePack));
        }

        Set<String> packsToCreate = new LinkedHashSet<>();
        if (req.has("packs") && req.get("packs").isJsonArray()) {
            for (JsonElement entry : req.getAsJsonArray("packs")) {
                if (entry.isJsonPrimitive()) {
                    packsToCreate.add(entry.getAsString());
                }
            }
        }

        if (req.has("files") && req.get("files").isJsonObject()) {
            JsonObject files = req.getAsJsonObject("files");
            for (String rel : files.keySet()) {
                Path target = storage.contentPacksDir().resolve(rel).normalize();
                if (!target.startsWith(storage.contentPacksDir())) {
                    throw new IllegalArgumentException("world file escapes content packs dir: " + rel);
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, files.get(rel).getAsString(), StandardCharsets.UTF_8);
                int slash = rel.indexOf('/');
                if (slash > 0) {
                    String pack = rel.substring(0, slash);
                    if (!EditorDemoSnapshotTest.PACKS.contains(pack)) {
                        Path packDir = storage.contentPacksDir().resolve(pack);
                        Path packYml = packDir.resolve("pack.yml");
                        if (Files.exists(packDir) && !Files.exists(packYml)) {
                            Files.writeString(packYml, "id: " + pack + "\n", StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }

        dispatcher = EditorDemoSnapshotTest.realOps(workspace, storage, true);

        for (String pack : packsToCreate) {
            if (!EditorDemoSnapshotTest.PACKS.contains(pack)
                    && !Files.exists(storage.contentPacksDir().resolve(pack))) {
                JsonObject create = new JsonObject();
                create.addProperty("pack", pack);
                JsonObject created = CodemodeBenchmarkOracleTest.dispatch(dispatcher, "edit.createPack", create);
                if (!created.get("ok").getAsBoolean()) {
                    return errorResponse(id, "ORACLE-CREATE-PACK", "failed to create pack " + pack + ": " + created.get("error"));
                }
            }
        }

        if (req.has("assets") && req.get("assets").isJsonObject()) {
            JsonObject assets = req.getAsJsonObject("assets");
            for (String assetPack : assets.keySet()) {
                for (JsonElement path : assets.getAsJsonArray(assetPack)) {
                    String assetPath = path.getAsString();
                    JsonObject put = new JsonObject();
                    put.addProperty("pack", assetPack);
                    put.addProperty("path", assetPath);
                    put.addProperty("data", assetPath.endsWith(".json")
                            ? Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8))
                            : PNG);
                    JsonObject putResp = CodemodeBenchmarkOracleTest.dispatch(dispatcher, "assets.put", put);
                    if (!putResp.get("ok").getAsBoolean()) {
                        return errorResponse(id, "ORACLE-ASSET-PUT", "failed to put asset " + assetPack + "/" + assetPath + ": " + putResp.get("error"));
                    }
                }
            }
        }

        JsonObject packsResp = CodemodeBenchmarkOracleTest.dispatch(dispatcher, "browse.packs", new JsonObject());
        if (!packsResp.get("ok").getAsBoolean()) {
            return errorResponse(id, "ORACLE-BROWSE-PACKS", "failed to list packs: " + packsResp.get("error"));
        }
        return okResponse(id, packsResp.get("data"));
    }

    private JsonObject dispatchRpc(JsonElement id, JsonObject req) {
        if (dispatcher == null) {
            return errorResponse(id, "ORACLE-NOT-READY", "oracle workspace not initialized; call resetWorld first");
        }
        String op = req.has("op") && !req.get("op").isJsonNull() ? req.get("op").getAsString() : "";
        JsonObject payload = req.has("payload") && req.get("payload").isJsonObject()
                ? req.getAsJsonObject("payload")
                : new JsonObject();
        JsonObject resp = CodemodeBenchmarkOracleTest.dispatch(dispatcher, op, payload);
        JsonObject out = new JsonObject();
        if (id != null) {
            out.add("id", id);
        }
        out.add("ok", resp.get("ok"));
        if (resp.has("data")) {
            out.add("data", resp.get("data"));
        }
        if (resp.has("error")) {
            out.add("error", resp.get("error"));
        }
        return out;
    }

    synchronized void cleanup() throws IOException {
        EditorDemoSnapshotTest.closeAll();
        dispatcher = null;
        if (workspace != null && Files.exists(workspace)) {
            try (Stream<Path> walk = Files.walk(workspace)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
            workspace = null;
        }
    }

    private static JsonObject okResponse(JsonElement id, JsonElement data) {
        JsonObject out = new JsonObject();
        if (id != null) {
            out.add("id", id);
        }
        out.addProperty("ok", true);
        if (data != null) {
            out.add("data", data);
        }
        return out;
    }

    private static JsonObject errorResponse(JsonElement id, String code, String message) {
        JsonObject out = new JsonObject();
        if (id != null) {
            out.add("id", id);
        }
        out.addProperty("ok", false);
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        out.add("error", err);
        return out;
    }
}
