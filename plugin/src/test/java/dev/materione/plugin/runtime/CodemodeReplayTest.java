package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.materione.core.storage.StorageService;
import dev.materione.testing.golden.GoldenFiles;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SDK core's staged operation lists, replayed against the real server (codemode.md section 3.9, issue 339).
 * Each golden under {@code fixtures/golden/codemode/ops/} is what the TypeScript core staged for a corpus program;
 * this test sends its {@code operations} through the real {@code authoring.transaction} op with {@code dryRun=true}
 * over the real registries and the fixture packs (plus the assets the program's world file lists), and pins the
 * outcome: whether the list was accepted, which validation stages ran, the diagnostics and the documents. The same
 * fixtures test both sides: a change to the core that the server refuses, or to the server that refuses a list
 * the core stages, fails here. Regenerate with {@code -Dmaterione.golden.regen=true}; a list the dry run refuses stays
 * in the corpus with its diagnostic pinned, and its {@code <task>.expect.json} says why under {@code replay.note}.
 */
class CodemodeReplayTest {

    private static final String PACK = "bench";
    /** A 1x1 PNG: enough for an asset the server only has to find. */
    private static final String PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    @TempDir
    Path temp;

    @AfterEach
    void close() {
        EditorDemoSnapshotTest.closeAll();
    }

    @Test
    void everyGoldenListReplaysAgainstTheRealServerWithItsPinnedOutcome() throws IOException {
        Path codemode = GoldenFiles.fixturesRoot().resolve("golden").resolve("codemode");
        Path opsDir = codemode.resolve("ops");
        Path replayDir = codemode.resolve("replay");
        Path corpus = GoldenFiles.fixturesRoot().resolve("codemode").resolve("corpus");
        List<Path> goldens;
        try (Stream<Path> list = Files.list(opsDir)) {
            goldens = list.filter(file -> file.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(goldens).as("the core's goldens").isNotEmpty();
        List<String> pinned = new ArrayList<>();
        List<String> unexplained = new ArrayList<>();
        for (Path golden : goldens) {
            String stem = golden.getFileName().toString().replaceFirst("\\.json$", "");
            JsonObject recorded =
                    JsonParser.parseString(Files.readString(golden, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject report = replay(recorded.getAsJsonArray("operations"), corpus.resolve(stem + ".world.json"));
            String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                    .toJson(outcome(stem, report)) + "\n";
            Path target = replayDir.resolve(stem + ".json");
            if (GoldenFiles.regenEnabled()) {
                Files.createDirectories(replayDir);
                Files.writeString(target, json, StandardCharsets.UTF_8);
            }
            assertThat(target).as("missing " + target + ": regenerate with -Dmaterione.golden.regen=true").exists();
            assertThat(json).as(stem).isEqualTo(Files.readString(target, StandardCharsets.UTF_8));
            pinned.add(stem);

            // a list the server refuses, or reports a problem on, is allowed only with a note naming the work that flips it
            boolean accepted = report.get("accepted").getAsBoolean();
            boolean problem = !accepted;
            for (JsonElement entry : report.getAsJsonArray("diagnostics")) {
                problem |= !"warning".equals(entry.getAsJsonObject().get("severity").getAsString());
            }
            JsonObject expect = JsonParser.parseString(
                    Files.readString(corpus.resolve(stem + ".expect.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            if (problem && !(expect.has("replay") && expect.getAsJsonObject("replay").has("note"))) {
                unexplained.add(stem);
            }
        }
        assertThat(unexplained)
                .as("refused or problem lists need a replay.note in their expect.json naming what flips them")
                .isEmpty();
        // a replay with no golden is a stale pin
        try (Stream<Path> list = Files.list(replayDir)) {
            assertThat(list.map(file -> file.getFileName().toString().replaceFirst("\\.json$", "")).sorted().toList())
                    .isEqualTo(pinned.stream().sorted().toList());
        }
    }

    /** One dry run on a fresh server: the sample and world packs, the bench pack, the world file's assets. */
    private JsonObject replay(JsonArray operations, Path worldFile) throws IOException {
        Path root = Files.createTempDirectory(temp, "server");
        StorageService.StorageState storage = new StorageService().init(root);
        for (String pack : EditorDemoSnapshotTest.PACKS) {
            EditorDemoSnapshotTest.copyTree(GoldenFiles.fixturesRoot().resolve("packs").resolve(pack),
                    storage.contentPacksDir().resolve(pack));
        }
        RpcDispatcher dispatcher = EditorDemoSnapshotTest.realOps(root, storage, true);
        call(dispatcher, "edit.createPack", object("pack", PACK));
        if (Files.exists(worldFile)) {
            JsonObject world =
                    JsonParser.parseString(Files.readString(worldFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject assets = world.has("assets") ? world.getAsJsonObject("assets") : new JsonObject();
            for (String pack : assets.keySet()) {
                for (JsonElement path : assets.getAsJsonArray(pack)) {
                    JsonObject put = object("pack", pack);
                    put.addProperty("path", path.getAsString());
                    put.addProperty("data", path.getAsString().endsWith(".json")
                            ? Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)) : PNG);
                    call(dispatcher, "assets.put", put);
                }
            }
        }
        JsonObject request = object("pack", PACK);
        request.addProperty("dryRun", true);
        request.add("operations", operations);
        return call(dispatcher, "authoring.transaction", request);
    }

    private static JsonObject call(RpcDispatcher dispatcher, String op, JsonObject payload) {
        Wire.Response response = dispatcher.dispatch(new Wire.Request(op, "replay", payload));
        assertThat(response.ok()).as(op + ": " + response.error()).isTrue();
        return response.data().getAsJsonObject();
    }

    private static JsonObject object(String key, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    /** What is pinned: acceptance, the stages that ran, the diagnostics, and the documents the dry run would write. */
    private static JsonObject outcome(String stem, JsonObject report) {
        JsonObject out = new JsonObject();
        out.addProperty("golden", stem);
        out.addProperty("accepted", report.get("accepted").getAsBoolean());
        out.addProperty("applied", report.get("applied").getAsBoolean());
        JsonArray stages = new JsonArray();
        for (JsonElement entry : report.getAsJsonArray("stages")) {
            JsonObject stage = new JsonObject();
            stage.add("id", entry.getAsJsonObject().get("id"));
            stage.add("ran", entry.getAsJsonObject().get("ran"));
            stages.add(stage);
        }
        out.add("stages", stages);
        JsonArray diagnostics = new JsonArray();
        for (JsonElement entry : report.getAsJsonArray("diagnostics")) {
            JsonObject source = entry.getAsJsonObject();
            JsonObject found = new JsonObject();
            for (String key : List.of("operation", "code", "severity", "message")) {
                if (source.has(key)) {
                    found.add(key, source.get(key));
                }
            }
            diagnostics.add(found);
        }
        out.add("diagnostics", diagnostics);
        JsonArray documents = new JsonArray();
        for (JsonElement entry : report.getAsJsonArray("documents")) {
            JsonObject source = entry.getAsJsonObject();
            JsonObject document = new JsonObject();
            document.add("path", source.get("path"));
            document.add("kind", source.get("kind"));
            document.add("operations", source.get("operations"));
            documents.add(document);
        }
        out.add("documents", documents);
        return out;
    }
}
