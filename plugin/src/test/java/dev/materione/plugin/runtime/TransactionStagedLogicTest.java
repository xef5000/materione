package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.materione.core.storage.StorageService;
import dev.materione.testing.golden.GoldenFiles;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The logic and behavior stage of a transaction's dry run sees the pack as the list leaves it (issue 378): a variable
 * an operation declares resolves for a rule another operation writes, in either order, in another document, and in a
 * GUI; and a created rule document gets the warning the shadow build raises for a trigger it never fires. The real
 * registries over the real authoring surfaces, the way {@code CodemodeReplayTest} replays a corpus list.
 */
class TransactionStagedLogicTest {

    private static final String PACK = "bench";

    private static final String VARIABLE = """
            {"kind":"create","type":"variables","id":"gems","edits":[
              {"field":["gems","scope"],"value":"player"},
              {"field":["gems","type"],"value":"number"},
              {"field":["gems","default"],"value":0}]}""";

    private static final String SET_GEMS = """
            {"set_var":{"var":"bench:gems","op":"set","value":1}}""";

    @TempDir
    Path temp;

    @AfterEach
    void close() {
        EditorDemoSnapshotTest.closeAll();
    }

    private RpcDispatcher server() throws IOException {
        Path root = Files.createTempDirectory(temp, "server");
        StorageService.StorageState storage = new StorageService().init(root);
        for (String fixturePack : EditorDemoSnapshotTest.PACKS) {
            EditorDemoSnapshotTest.copyTree(GoldenFiles.fixturesRoot().resolve("packs").resolve(fixturePack),
                    storage.contentPacksDir().resolve(fixturePack));
        }
        RpcDispatcher dispatcher = EditorDemoSnapshotTest.realOps(root, storage, true);
        JsonObject create = new JsonObject();
        create.addProperty("pack", PACK);
        call(dispatcher, "edit.createPack", create);
        return dispatcher;
    }

    private static JsonObject call(RpcDispatcher dispatcher, String op, JsonObject payload) {
        Wire.Response response = dispatcher.dispatch(new Wire.Request(op, "test", payload));
        assertThat(response.ok()).as(op + ": " + response.error()).isTrue();
        return response.data().getAsJsonObject();
    }

    private JsonObject dryRun(RpcDispatcher dispatcher, String... operations) {
        JsonObject request = JsonParser.parseString(
                "{\"pack\":\"" + PACK + "\",\"dryRun\":true,\"operations\":[" + String.join(",", operations) + "]}")
                .getAsJsonObject();
        return call(dispatcher, "authoring.transaction", request);
    }

    private static List<String> findings(JsonObject report) {
        List<String> out = new ArrayList<>();
        for (JsonElement entry : report.getAsJsonArray("diagnostics")) {
            JsonObject diagnostic = entry.getAsJsonObject();
            out.add(diagnostic.get("operation").getAsInt() + " " + diagnostic.get("code").getAsString()
                    + " " + diagnostic.get("severity").getAsString());
        }
        return out;
    }

    private static boolean stageRan(JsonObject report, String id) {
        JsonArray stages = report.getAsJsonArray("stages");
        for (JsonElement stage : stages) {
            if (stage.getAsJsonObject().get("id").getAsString().equals(id)) {
                return stage.getAsJsonObject().get("ran").getAsBoolean();
            }
        }
        throw new AssertionError("no stage " + id);
    }

    private static String itemWithRule() {
        return """
                {"kind":"create","type":"items","id":"token","edits":[
                  {"field":["token","material"],"value":"PAPER"},
                  {"field":["token","behavior"],"value":[{"on":"click","do":[%s]}]}]}""".formatted(SET_GEMS);
    }

    @Test
    void aVariableDeclaredByAnEarlierOperationResolvesForAnItemRule() throws IOException {
        JsonObject report = dryRun(server(), VARIABLE, itemWithRule());

        assertThat(stageRan(report, "behavior")).isTrue();
        assertThat(findings(report)).isEmpty();
        assertThat(report.get("accepted").getAsBoolean()).isTrue();
    }

    @Test
    void aVariableDeclaredByALaterOperationResolvesToo() throws IOException {
        JsonObject report = dryRun(server(), itemWithRule(), VARIABLE);

        assertThat(findings(report)).isEmpty();
        assertThat(report.get("accepted").getAsBoolean()).isTrue();
    }

    @Test
    void aVariableNobodyDeclaresIsStillRefusedAtTheRuleOperation() throws IOException {
        JsonObject report = dryRun(server(), itemWithRule());

        assertThat(findings(report)).contains("0 MTR-0063 error");
        assertThat(report.get("accepted").getAsBoolean()).isFalse();
    }

    @Test
    void aVariableResolvesAcrossDocumentsForAnItemAndAGui() throws IOException {
        String gui = """
                {"kind":"create","type":"guis","id":"hub","edits":[
                  {"field":["hub","container"],"value":"chest_3"},
                  {"field":["hub","title"],"value":"Hub"},
                  {"field":["hub","layers"],"value":[
                    {"type":"materione:button","id":"go","x":70,"y":30,"width":36,"height":20}]}]}""";
        String guiRule = """
                {"kind":"logicEdit","path":"guis/hub.yml","edits":[
                  {"op":"addRule","index":0,"rule":{"on":"gui.click","layer":"go","do":[%s]}}]}""".formatted(SET_GEMS);
        JsonObject report = dryRun(server(), gui, guiRule, VARIABLE,
                itemWithRule());

        assertThat(findings(report)).isEmpty();
    }

    @Test
    void aCreatedRuleDocumentWithATriggerItNeverFiresIsWarned() throws IOException {
        String rule = """
                {"kind":"create","type":"rules","id":"loot","edits":[
                  {"field":["loot","behavior"],"value":[{"on":"click","do":[{"message":"hi"}]}]}]}""";
        JsonObject report = dryRun(server(), rule);

        assertThat(findings(report)).containsExactly("0 MTR-0021 warn");
        assertThat(report.getAsJsonArray("diagnostics").get(0).getAsJsonObject().get("message").getAsString())
                .contains("is not fired for rule documents");
        assertThat(report.get("accepted").getAsBoolean()).isTrue();
    }

    /** Applies a list (not a dry run). */
    private JsonObject apply(RpcDispatcher dispatcher, String... operations) {
        JsonObject request = JsonParser.parseString(
                "{\"pack\":\"" + PACK + "\",\"dryRun\":false,\"operations\":[" + String.join(",", operations) + "]}")
                .getAsJsonObject();
        return call(dispatcher, "authoring.transaction", request);
    }

    @Test
    void deletingAnItemAGuiSlotAndAStaticStepNameIsReportedAgainstTheDeletingOperation() throws IOException {
        RpcDispatcher server = server();
        String token = """
                {"kind":"create","type":"items","id":"token","edits":[
                  {"field":["token","material"],"value":"PAPER"}]}""";
        String hub = """
                {"kind":"create","type":"guis","id":"hub","edits":[
                  {"field":["hub","container"],"value":"chest_3"},
                  {"field":["hub","title"],"value":"Hub"},
                  {"field":["hub","layers"],"value":[
                    {"type":"materione:item","id":"slot","slot":4,"item":"bench:token"},
                    {"type":"materione:button","id":"go","x":70,"y":30,"width":36,"height":20}]},
                  {"field":["hub","behavior"],"value":[
                    {"on":"gui.click","layer":"go","do":[
                      {"give_item":"bench:token"},
                      {"gui.open":{"gui":"bench:hub"}},
                      {"give_item":"%player_name%"}]}]}]}""";
        JsonObject applied = apply(server, token, hub);
        assertThat(applied.get("accepted").getAsBoolean()).as(String.valueOf(applied)).isTrue();
        assertThat(findings(applied)).as("a pack that names only what it declares is clean").isEmpty();
        int revision = 1;

        String delete = "{\"kind\":\"delete\",\"path\":\"items/token.yml\",\"rev\":" + revision + "}";
        JsonObject report = dryRun(server, delete);

        assertThat(report.get("accepted").getAsBoolean()).as("warnings never refuse").isTrue();
        assertThat(findings(report)).contains("0 MTR-0054 warn", "0 MTR-0105 warn");
        assertThat(findings(report)).as("a templated id is an expression, not checked")
                .filteredOn(finding -> finding.contains("MTR-0105")).hasSize(1);
    }

    @Test
    void aTriggerTheRuleHostFiresIsNotWarned() throws IOException {
        String rule = """
                {"kind":"create","type":"rules","id":"loot","edits":[
                  {"field":["loot","behavior"],"value":[{"on":"entity.kill","do":[{"message":"hi"}]}]}]}""";

        assertThat(findings(dryRun(server(), rule))).isEmpty();
    }
}
