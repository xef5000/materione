package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CodemodeBenchmarkOracleTest {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    static void registerShutdownHook(CodemodeBenchmarkOracleMain oracle) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                oracle.cleanup();
            } catch (IOException ignored) {
            }
        }, "benchmark-oracle-cleanup"));
    }

    static JsonObject dispatch(RpcDispatcher dispatcher, String op, JsonObject payload) {
        Wire.Response resp = dispatcher.dispatch(new Wire.Request(op, "bench-oracle", payload));
        JsonObject out = new JsonObject();
        out.addProperty("ok", resp.ok());
        if (resp.data() != null) {
            out.add("data", resp.data());
        }
        if (resp.error() != null) {
            out.add("error", GSON.toJsonTree(resp.error()));
        }
        return out;
    }

    private final CodemodeBenchmarkOracleMain oracle = new CodemodeBenchmarkOracleMain();

    @AfterEach
    void tearDown() throws IOException {
        oracle.cleanup();
    }

    @Test
    void supportsPingResetWorldDescribeTransactionDryRunAndApplyAndFollowupReadEdit() throws IOException {
        // 1. ping
        JsonObject pingReq = new JsonObject();
        pingReq.addProperty("id", 1);
        pingReq.addProperty("cmd", "ping");
        JsonObject pingResp = oracle.handle(pingReq);
        assertThat(pingResp.get("ok").getAsBoolean()).isTrue();
        assertThat(pingResp.getAsJsonObject("data").get("ready").getAsBoolean()).isTrue();

        // 2. resetWorld with custom pack and pre-seeded file
        JsonObject resetReq = new JsonObject();
        resetReq.addProperty("id", 2);
        resetReq.addProperty("cmd", "resetWorld");
        JsonArray packs = new JsonArray();
        packs.add("bench");
        resetReq.add("packs", packs);
        JsonObject files = new JsonObject();
        files.addProperty("bench/items/seed_blade.yml", "seed_blade:\n  material: IRON_SWORD\n  display_name: \"Seed Blade\"\n");
        resetReq.add("files", files);
        JsonObject resetResp = oracle.handle(resetReq);
        assertThat(resetResp.get("ok").getAsBoolean()).isTrue();
        assertThat(resetResp.getAsJsonObject("data").getAsJsonArray("packs").toString()).contains("bench");

        // 3. authoring.describe
        JsonObject describeReq = rpc(3, "authoring.describe", new JsonObject());
        JsonObject describeResp = oracle.handle(describeReq);
        assertThat(describeResp.get("ok").getAsBoolean()).isTrue();
        assertThat(describeResp.getAsJsonObject("data").has("types")).isTrue();

        // 4. authoring.transaction dryRun=true create
        JsonObject createOp = new JsonObject();
        createOp.addProperty("kind", "create");
        createOp.addProperty("type", "items");
        createOp.addProperty("id", "bench_coin");
        JsonArray edits = new JsonArray();
        JsonObject matEdit = new JsonObject();
        JsonArray matPath = new JsonArray();
        matPath.add("bench_coin");
        matPath.add("material");
        matEdit.add("field", matPath);
        matEdit.addProperty("value", "GOLD_NUGGET");
        edits.add(matEdit);
        createOp.add("edits", edits);

        JsonArray ops = new JsonArray();
        ops.add(createOp);

        JsonObject dryPayload = new JsonObject();
        dryPayload.addProperty("pack", "bench");
        dryPayload.addProperty("dryRun", true);
        dryPayload.add("operations", ops);
        JsonObject dryResp = oracle.handle(rpc(4, "authoring.transaction", dryPayload));
        assertThat(dryResp.get("ok").getAsBoolean()).isTrue();
        assertThat(dryResp.getAsJsonObject("data").get("accepted").getAsBoolean()).isTrue();
        assertThat(dryResp.getAsJsonObject("data").get("applied").getAsBoolean()).isFalse();

        // 5. authoring.transaction dryRun=false create
        JsonObject applyPayload = dryPayload.deepCopy();
        applyPayload.addProperty("dryRun", false);
        applyPayload.addProperty("origin", "code-mode");
        applyPayload.addProperty("approval", "not_needed");
        JsonObject applyResp = oracle.handle(rpc(5, "authoring.transaction", applyPayload));
        assertThat(applyResp.get("ok").getAsBoolean()).isTrue();
        assertThat(applyResp.getAsJsonObject("data").get("accepted").getAsBoolean()).isTrue();
        assertThat(applyResp.getAsJsonObject("data").get("applied").getAsBoolean()).isTrue();

        // 6. follow-up browse.get + setFields
        JsonObject getPayload = new JsonObject();
        getPayload.addProperty("pack", "bench");
        getPayload.addProperty("path", "items/bench_coin.yml");
        JsonObject getResp = oracle.handle(rpc(6, "browse.get", getPayload));
        assertThat(getResp.get("ok").getAsBoolean()).isTrue();
        int rev = getResp.getAsJsonObject("data").get("rev").getAsInt();

        JsonObject setOp = new JsonObject();
        setOp.addProperty("kind", "setFields");
        setOp.addProperty("path", "items/bench_coin.yml");
        setOp.addProperty("rev", rev);
        JsonArray setEdits = new JsonArray();
        JsonObject nameEdit = new JsonObject();
        JsonArray namePath = new JsonArray();
        namePath.add("bench_coin");
        namePath.add("display_name");
        nameEdit.add("field", namePath);
        nameEdit.addProperty("value", "<gold>Bench Coin");
        setEdits.add(nameEdit);
        setOp.add("edits", setEdits);

        JsonArray followOps = new JsonArray();
        followOps.add(setOp);
        JsonObject followPayload = new JsonObject();
        followPayload.addProperty("pack", "bench");
        followPayload.addProperty("dryRun", false);
        followPayload.addProperty("origin", "code-mode");
        followPayload.addProperty("approval", "granted");
        followPayload.add("operations", followOps);
        JsonObject followResp = oracle.handle(rpc(7, "authoring.transaction", followPayload));
        assertThat(followResp.get("ok").getAsBoolean()).isTrue();
        assertThat(followResp.getAsJsonObject("data").get("applied").getAsBoolean()).isTrue();

        // 7. close
        JsonObject closeReq = new JsonObject();
        closeReq.addProperty("id", 8);
        closeReq.addProperty("cmd", "close");
        JsonObject closeResp = oracle.handle(closeReq);
        assertThat(closeResp.get("ok").getAsBoolean()).isTrue();
        assertThat(closeResp.getAsJsonObject("data").get("closed").getAsBoolean()).isTrue();
    }

    @Test
    void authoringSummariesWithLimit200FindsPreSeededGui() throws IOException {
        JsonObject resetReq = new JsonObject();
        resetReq.addProperty("id", 1);
        resetReq.addProperty("cmd", "resetWorld");
        JsonArray packs = new JsonArray();
        packs.add("bench");
        resetReq.add("packs", packs);
        JsonObject files = new JsonObject();
        files.addProperty("bench/guis/bloodfang_menu.yml",
                "bloodfang_menu:\n  title: \"<red>Bloodfang\"\n  container: chest_3\n  layers: []\n");
        resetReq.add("files", files);
        JsonObject resetResp = oracle.handle(resetReq);
        assertThat(resetResp.get("ok").getAsBoolean()).isTrue();

        JsonObject sumPayload = new JsonObject();
        sumPayload.addProperty("pack", "bench");
        sumPayload.addProperty("type", "guis");
        sumPayload.addProperty("offset", 0);
        sumPayload.addProperty("limit", 200);
        JsonObject sumResp = oracle.handle(rpc(2, "authoring.summaries", sumPayload));
        assertThat(sumResp.get("ok").getAsBoolean()).isTrue();
        JsonArray resources = sumResp.getAsJsonObject("data").getAsJsonArray("resources");
        assertThat(resources).isNotNull();
        assertThat(resources.toString()).contains("bloodfang_menu");
    }

    private static JsonObject rpc(int id, String op, JsonObject payload) {
        JsonObject req = new JsonObject();
        req.addProperty("id", id);
        req.addProperty("cmd", "rpc");
        req.addProperty("op", op);
        req.add("payload", payload);
        return req;
    }
}
