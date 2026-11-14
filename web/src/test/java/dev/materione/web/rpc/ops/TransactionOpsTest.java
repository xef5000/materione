package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.content.AllocatedRange;
import dev.materione.api.content.Allocations;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.behavior.logic.authoring.LogicAuthoringApiImpl;
import dev.materione.content.gui.GuiAuthoringApiImpl;
import dev.materione.content.gui.GuiComponents;
import dev.materione.core.authoring.AuthoringApiImpl;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.Documents;
import dev.materione.core.document.ModelTransactionService;
import dev.materione.core.registry.RegistryService;
import dev.materione.testing.golden.GoldenFiles;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code authoring.transaction} through the real dispatcher over the real logic and GUI authoring surfaces: the
 * benchmark's mixed list (an item, a GUI, its layers and rules, a change to an existing item) dry-runs with the
 * resulting documents, applies atomically, and leaves every untouched document byte-identical. The frames are
 * pinned in {@code fixtures/protocol/editor-codemode-v1.json}. Regenerate with {@code -Dmaterione.golden.regen=true}.
 */
class TransactionOpsTest {

    private static final String FILE_NAME = "editor-codemode-v1.json";
    private static final String PACK = "demo";
    private static final String SWORD = """
            # the shop's best seller
            sword:
              display_name: Sword
              material: IRON_SWORD # base
            """;
    private static final String UNTOUCHED = "untouched:\n  # never edited\n  material: BONE\n\n  texture:   spaced\n";

    @TempDir
    Path temp;

    @Test
    void theMixedListDryRunsThenAppliesAtomically() throws Exception {
        Session session = session();
        List<String> before = snapshot();

        Wire.Response dry = session.dispatcher.dispatch(mixed("t-1", true));
        assertThat(dry.ok()).as(String.valueOf(dry.error())).isTrue();
        JsonObject report = dry.data().getAsJsonObject();
        assertThat(report.get("accepted").getAsBoolean()).as(report.get("diagnostics").toString()).isTrue();
        assertThat(report.get("applied").getAsBoolean()).isFalse();
        assertThat(report.get("transactionId").isJsonNull()).isTrue();
        assertThat(snapshot()).as("a dry run writes nothing").isEqualTo(before);
        assertThat(session.reloads).isEmpty();

        List<String> paths = new ArrayList<>();
        report.getAsJsonArray("documents").forEach(row -> paths.add(row.getAsJsonObject().get("path").getAsString()));
        assertThat(paths).containsExactly("items/ruby_blade.yml", "guis/shop.yml", "items/sword.yml");
        JsonObject shop = row(report, "guis/shop.yml");
        assertThat(shop.get("kind").getAsString()).isEqualTo("created");
        assertThat(shop.get("rev").getAsInt()).isEqualTo(1);
        assertThat(shop.get("operations").toString()).isEqualTo("[1,2,4]");
        assertThat(shop.get("text").getAsString()).contains("title: \"Shop\"", "id: frame", "on: click", "give: coin");
        JsonObject blade = row(report, "items/ruby_blade.yml");
        assertThat(blade.get("text").getAsString()).contains("texture: ruby", "behavior:", "give: gem");
        JsonObject sword = row(report, "items/sword.yml");
        assertThat(sword.get("kind").getAsString()).isEqualTo("changed");
        assertThat(sword.get("previousRevision").getAsInt()).isEqualTo(1);
        assertThat(sword.get("rev").getAsInt()).isEqualTo(2);
        assertThat(sword.get("text").getAsString()).contains("# the shop's best seller", "material: IRON_SWORD # base",
                "texture: steel");
        // the flow compiler and the GUI compile really ran on the result
        assertThat(stageRan(report, "behavior")).isTrue();
        assertThat(stageRan(report, "gui")).isTrue();
        assertThat(stageRan(report, "references")).isFalse();

        Wire.Response applied = session.dispatcher.dispatch(mixed("t-2", false));
        assertThat(applied.ok()).as(String.valueOf(applied.error())).isTrue();
        JsonObject done = applied.data().getAsJsonObject();
        assertThat(done.get("applied").getAsBoolean()).isTrue();
        assertThat(done.get("transactionId").getAsString()).startsWith("tx-");
        // what was checked is what is applied
        assertThat(done.get("documents")).isEqualTo(report.get("documents"));
        assertThat(Files.readString(path("guis/shop.yml"))).isEqualTo(shop.get("text").getAsString());
        assertThat(Files.readString(path("items/ruby_blade.yml"))).isEqualTo(blade.get("text").getAsString());
        assertThat(Files.readString(path("items/sword.yml"))).isEqualTo(sword.get("text").getAsString());
        // the untouched document is byte-identical, and its journal did not move
        assertThat(Files.readString(path("items/untouched.yml"))).isEqualTo(UNTOUCHED);
        assertThat(session.authoring.getHistory(PACK, "items/untouched.yml", 10).value()).hasSize(1);
        assertThat(session.authoring.getHistory(PACK, "items/sword.yml", 10).value()).hasSize(2);
        assertThat(session.reloads).hasSize(1);
        assertThat(Files.readString(temp.resolve("audit.log"))).contains("authoring.transaction")
                .contains(done.get("transactionId").getAsString());
    }

    @Test
    void aRefusedEditIsASuccessFrameWithTheOperationThatCausedIt() throws Exception {
        Session session = session();
        List<String> before = snapshot();

        Wire.Response refused = session.dispatcher.dispatch(frame("t-3", """
                {"pack":"demo","dryRun":false,"operations":[
                  {"kind":"create","type":"guis","id":"shop","name":"Shop"},
                  {"kind":"guiEdit","path":"guis/shop.yml","edits":[
                    {"op":"addLayer","index":0,"type":"nosuchcomponent","id":"frame"}]}]}"""));

        assertThat(refused.ok()).as("a refusal is a report, not an error frame").isTrue();
        JsonObject report = refused.data().getAsJsonObject();
        assertThat(report.get("accepted").getAsBoolean()).isFalse();
        assertThat(report.get("applied").getAsBoolean()).isFalse();
        JsonObject found = report.getAsJsonArray("diagnostics").get(0).getAsJsonObject();
        assertThat(found.get("operation").getAsInt()).isEqualTo(1);
        assertThat(found.get("code").getAsString()).isEqualTo("MTR-0008");
        assertThat(found.get("message").getAsString()).contains("nosuchcomponent");
        assertThat(snapshot()).isEqualTo(before);

        Wire.Response badLogic = session.dispatcher.dispatch(frame("t-4", """
                {"pack":"demo","dryRun":true,"operations":[
                  {"kind":"logicEdit","path":"items/sword.yml","rev":1,"edits":[
                    {"op":"insertStep","path":"behavior.0.do.0","step":{"nosuch":1}}]}]}"""));
        assertThat(badLogic.data().getAsJsonObject().get("accepted").getAsBoolean()).isFalse();
    }

    /** The reference stage over the wire (#357): a delete that orphans a user is refused, and the report says so. */
    @Test
    void aDeleteThatOrphansAReferenceIsRefusedOnTheWireWithTheOperationAndTheStageSaysItRan() throws Exception {
        Session session = session();
        ((AuthoringApiImpl) session.authoring()).parsedContent(() -> dev.materione.core.pipeline.ParsedContent.EMPTY);
        session.authoring().putDocument(ActorContext.console(), new AuthoringApi.PutDocumentRequest(
                PACK, "recipes/forge.yml", 0, "forge:\n  type: shapeless\n  result: sword\n"));
        session.reloads.clear();
        List<String> before = snapshot();

        for (boolean dryRun : new boolean[] {true, false}) {
            Wire.Response refused = session.dispatcher.dispatch(frame("t-ref-" + dryRun, """
                    {"pack":"demo","dryRun":%s,"operations":[
                      {"kind":"delete","path":"items/sword.yml","rev":1}]}""".formatted(dryRun)));

            assertThat(refused.ok()).isTrue();
            JsonObject report = refused.data().getAsJsonObject();
            assertThat(report.get("accepted").getAsBoolean()).isFalse();
            assertThat(report.get("applied").getAsBoolean()).isFalse();
            JsonObject found = report.getAsJsonArray("diagnostics").get(0).getAsJsonObject();
            assertThat(found.get("operation").getAsInt()).isEqualTo(0);
            assertThat(found.get("code").getAsString())
                    .isEqualTo(dev.materione.api.diagnostics.DiagnosticCodes.UNRESOLVED_REFERENCE.code());
            assertThat(found.get("message").getAsString()).contains("forge", "sword");
            assertThat(stageRan(report, "references")).isTrue();
            assertThat(stageRan(report, "platform")).isFalse();
            assertThat(snapshot()).as("refused, so nothing written (dryRun=" + dryRun + ")").isEqualTo(before);
        }
        assertThat(session.reloads).isEmpty();
    }

    @Test
    void aMalformedRequestIsABadRequestAndALostFlagNeverBecomesAWrite() throws Exception {
        Session session = session();
        List<String> before = snapshot();

        Wire.Response noFlag = session.dispatcher.dispatch(frame("m-1", """
                {"pack":"demo","operations":[{"kind":"delete","path":"items/sword.yml","rev":1}]}"""));
        assertThat(noFlag.ok()).isFalse();
        assertThat(noFlag.error().code()).isEqualTo("MTR-0008");
        assertThat(noFlag.error().message()).contains("dryRun");

        Wire.Response unknownKind = session.dispatcher.dispatch(frame("m-2", """
                {"pack":"demo","dryRun":true,"operations":[{"kind":"explode"}]}"""));
        assertThat(unknownKind.ok()).isFalse();
        assertThat(unknownKind.error().code()).isEqualTo("MTR-0008");

        Wire.Response empty = session.dispatcher.dispatch(frame("m-3", """
                {"pack":"demo","dryRun":true,"operations":[]}"""));
        assertThat(empty.ok()).isFalse();
        assertThat(snapshot()).isEqualTo(before);
    }

    /** Revert over the wire (#344): the same report, the apply's id is the key, and the store comes back byte for byte. */
    @Test
    void aTransactionIsRevertedByItsIdOverTheWireAndTheStoreComesBack() throws Exception {
        Session session = session();
        List<String> files = packFiles();

        Wire.Response applied = session.dispatcher.dispatch(mixed("v-1", false));
        JsonObject done = applied.data().getAsJsonObject();
        assertThat(done.get("revertible").getAsBoolean()).isTrue();
        assertThat(done.get("revertNote").isJsonNull()).isTrue();
        assertThat(done.get("revertOf").isJsonNull()).isTrue();
        String id = done.get("transactionId").getAsString();
        session.reloads.clear();

        Wire.Response dry = session.dispatcher.dispatch(revertFrame("v-2", id, true));
        assertThat(dry.ok()).as(String.valueOf(dry.error())).isTrue();
        JsonObject preview = dry.data().getAsJsonObject();
        assertThat(preview.get("accepted").getAsBoolean()).isTrue();
        assertThat(preview.get("applied").getAsBoolean()).isFalse();
        assertThat(preview.get("revertOf").getAsString()).isEqualTo(id);
        assertThat(row(preview, "items/ruby_blade.yml").get("kind").getAsString()).isEqualTo("deleted");
        assertThat(row(preview, "items/sword.yml").get("text").getAsString()).contains("material: IRON_SWORD # base")
                .doesNotContain("texture: steel");
        assertThat(session.reloads).isEmpty();

        Wire.Response reverted = session.dispatcher.dispatch(revertFrame("v-3", id, false));
        assertThat(reverted.ok()).as(String.valueOf(reverted.error())).isTrue();
        JsonObject report = reverted.data().getAsJsonObject();
        assertThat(report.get("applied").getAsBoolean()).isTrue();
        assertThat(report.get("revertOf").getAsString()).isEqualTo(id);
        assertThat(report.get("transactionId").getAsString()).startsWith("tx-").isNotEqualTo(id);
        assertThat(report.get("documents")).as("what the dry run checked is what is applied").isEqualTo(preview.get("documents"));
        assertThat(packFiles()).isEqualTo(files);
        assertThat(session.reloads).hasSize(1);
        assertThat(Files.readString(temp.resolve("audit.log"))).contains("authoring.revertTransaction", "reverts " + id);

        // the revert's own id is in the journal of every document it wrote
        assertThat(session.authoring.getHistory(PACK, "items/sword.yml", 10).value()).hasSize(3);
    }

    @Test
    void theAuditContextOfATransactionRidesTheWire() throws Exception {
        Session session = session();
        Wire.Response applied = session.dispatcher.dispatch(frame("a-1", """
                {"pack":"demo","dryRun":false,"origin":"code-mode","approval":"granted",
                 "program":"export default (m) => m.create('item', {id: 'ex'})","operations":[
                  {"kind":"create","type":"items","id":"ex","name":"X"}]}"""));
        assertThat(applied.ok()).as(String.valueOf(applied.error())).isTrue();
        assertThat(applied.data().getAsJsonObject().get("applied").getAsBoolean()).as(applied.data().toString()).isTrue();
        String id = applied.data().getAsJsonObject().get("transactionId").getAsString();

        Wire.Response audit = auditTail(session);

        JsonObject entry = null;
        for (JsonElement element : audit.data().getAsJsonObject().getAsJsonArray("entries")) {
            if (id.equals(element.getAsJsonObject().has("transaction")
                    ? element.getAsJsonObject().get("transaction").getAsString() : null)) {
                entry = element.getAsJsonObject();
            }
        }
        assertThat(entry).as(audit.data().toString()).isNotNull();
        assertThat(entry.get("origin").getAsString()).isEqualTo("code-mode");
        assertThat(entry.get("approval").getAsString()).isEqualTo("granted");
        assertThat(entry.get("program").getAsString()).contains("export default");
        assertThat(entry.get("digest").getAsString()).startsWith("sha256:");
        assertThat(entry.get("programLength").getAsInt()).isEqualTo(
                "export default (m) => m.create('item', {id: 'ex'})".length());

        // a bad origin is a refused list (a report), never a write
        Wire.Response bad = session.dispatcher.dispatch(frame("a-2", """
                {"pack":"demo","dryRun":false,"origin":"hacker","operations":[
                  {"kind":"create","type":"items","id":"wy","name":"Y"}]}"""));
        assertThat(bad.data().getAsJsonObject().get("applied").getAsBoolean()).isFalse();
        assertThat(path("items/wy.yml")).doesNotExist();
    }

    @Test
    void aMalformedRevertRequestIsABadRequestAndAnUnknownIdIsAReport() throws Exception {
        Session session = session();
        Wire.Response noFlag = session.dispatcher.dispatch(new Wire.Request("authoring.revertTransaction", "b-1",
                JsonParser.parseString("{\"transactionId\":\"tx-x\"}").getAsJsonObject()));
        assertThat(noFlag.ok()).isFalse();
        assertThat(noFlag.error().code()).isEqualTo("MTR-0008");
        Wire.Response noId = session.dispatcher.dispatch(new Wire.Request("authoring.revertTransaction", "b-2",
                JsonParser.parseString("{\"dryRun\":true}").getAsJsonObject()));
        assertThat(noId.ok()).isFalse();

        Wire.Response unknown = session.dispatcher.dispatch(revertFrame("b-3", "tx-00000000-0000-0000-0000-000000000000", false));
        assertThat(unknown.ok()).isTrue();
        JsonObject report = unknown.data().getAsJsonObject();
        assertThat(report.get("accepted").getAsBoolean()).isFalse();
        assertThat(report.getAsJsonArray("diagnostics").get(0).getAsJsonObject().get("code").getAsString())
                .isEqualTo("MTR-0104");
    }

    /** What an editor reads from the audit tail: the op the admin surface registers, over the same log. */
    private Wire.Response auditTail(Session session) {
        var dispatcher = new RpcDispatcher();
        AuditLog log = new AuditLog(temp.resolve("audit.log"));
        dev.materione.api.admin.AdminApi admin = (dev.materione.api.admin.AdminApi) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {dev.materione.api.admin.AdminApi.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("recentAudit")) {
                        List<dev.materione.api.admin.AdminApi.AuditEntry> out = new ArrayList<>();
                        for (AuditLog.Entry entry : log.recent((Integer) args[0])) {
                            out.add(new dev.materione.api.admin.AdminApi.AuditEntry(entry.ts(), entry.actor(),
                                    entry.op(), entry.target(), entry.origin(), entry.approval(), entry.transaction(),
                                    entry.program(), entry.digest(), entry.programLength()));
                        }
                        return out;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        AdminOps.register(dispatcher, admin, null, attribution -> ActorContext.console(), (actor, target) -> { });
        return dispatcher.dispatch(new Wire.Request("admin.audit.recent", "au-1",
                JsonParser.parseString("{\"limit\":50}").getAsJsonObject()));
    }

    /** Every file under the packs, path and content hash (directories are organisational). */
    private List<String> packFiles() {
        List<String> lines = new ArrayList<>();
        Path root = temp.resolve("packs");
        try (var walk = Files.walk(root)) {
            walk.sorted().filter(Files::isRegularFile).forEach(file -> {
                try {
                    lines.add(root.relativize(file).toString().replace('\\', '/') + " "
                            + Documents.sha256(Files.readAllBytes(file)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return lines;
    }

    @Test
    void goldenFixtureIsStable() throws IOException {
        JsonObject built = build();
        Path target = GoldenFiles.fixturesRoot().resolve("protocol").resolve(FILE_NAME);
        if (GoldenFiles.regenEnabled()) {
            Files.createDirectories(target.getParent());
            Files.writeString(target, pretty(built), StandardCharsets.UTF_8);
            return;
        }
        assertThat(target).exists().withFailMessage(
                "missing " + target + " - regenerate once with -Dmaterione.golden.regen=true");
        assertThat(pretty(built)).isEqualTo(Files.readString(target, StandardCharsets.UTF_8));
    }

    /** Runs each frame through the real dispatcher in a fixed order, and records the pairs. */
    private JsonObject build() {
        Session session = session();
        var root = new JsonObject();
        var meta = new JsonObject();
        meta.addProperty("file", FILE_NAME);
        meta.addProperty("name", "materione authoring.transaction frames");
        meta.addProperty("version", 1);
        meta.addProperty("note", "transactionId is random in a real reply; the fixture pins it to tx-fixture");
        root.add("meta", meta);
        root.add("dryRun", pair(session.dispatcher, mixed("t-1", true)));
        Wire.Request applyRequest = mixed("t-2", false);
        Wire.Response applyResponse = session.dispatcher.dispatch(applyRequest);
        String appliedId = applyResponse.data().getAsJsonObject().get("transactionId").getAsString();
        root.add("apply", record(applyRequest, applyResponse, Map.of(appliedId, "tx-fixture")));
        // the sword moved to revision 2 by the apply: a list written against revision 1 is stale
        root.add("stale", pair(session.dispatcher, frame("t-3", """
                {"pack":"demo","dryRun":true,"operations":[
                  {"kind":"setFields","path":"items/sword.yml","rev":1,"edits":[
                    {"field":["sword","texture"],"value":"gold"}]}]}""")));
        root.add("refused", pair(session.dispatcher, frame("t-4", """
                {"pack":"demo","dryRun":true,"operations":[
                  {"kind":"create","type":"guis","id":"vault","name":"Vault"},
                  {"kind":"guiEdit","path":"guis/vault.yml","edits":[
                    {"op":"addLayer","index":0,"type":"nosuchcomponent","id":"frame"}]}]}""")));
        // revert (#344): the apply above is undone by its id. A dry run reports, the apply writes, and a second
        // revert is refused because the documents are no longer as the transaction left them
        Wire.Request dryRevert = revertFrame("r-1", appliedId, true);
        root.add("revertDryRun", record(dryRevert, session.dispatcher.dispatch(dryRevert), Map.of(appliedId, "tx-fixture")));
        Wire.Request revert = revertFrame("r-2", appliedId, false);
        Wire.Response reverted = session.dispatcher.dispatch(revert);
        String revertId = reverted.data().getAsJsonObject().get("transactionId").getAsString();
        root.add("revert", record(revert, reverted, Map.of(appliedId, "tx-fixture", revertId, "tx-fixture-revert")));
        Wire.Request again = revertFrame("r-3", appliedId, false);
        root.add("revertStale", record(again, session.dispatcher.dispatch(again), Map.of(appliedId, "tx-fixture")));
        Wire.Request unknown = revertFrame("r-4", "tx-00000000-0000-0000-0000-000000000000", false);
        root.add("revertUnknown", record(unknown, session.dispatcher.dispatch(unknown), Map.of()));
        return root;
    }

    private static Wire.Request revertFrame(String id, String transactionId, boolean dryRun) {
        return new Wire.Request("authoring.revertTransaction", id, JsonParser.parseString(
                "{\"transactionId\":\"" + transactionId + "\",\"dryRun\":" + dryRun + "}").getAsJsonObject());
    }

    /** A request and its response, with the random transaction ids a real reply carries pinned to stable names. */
    private static JsonObject record(Wire.Request request, Wire.Response response, Map<String, String> pinned) {
        String json = "{\"request\":" + request.toJson() + ",\"response\":" + response.toJson() + "}";
        for (Map.Entry<String, String> pin : pinned.entrySet()) {
            json = json.replace(pin.getKey(), pin.getValue());
        }
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject pair(RpcDispatcher dispatcher, Wire.Request request) {
        Wire.Response response = dispatcher.dispatch(request);
        var pair = new JsonObject();
        pair.add("request", request.toJson());
        JsonObject json = response.toJson();
        if (response.ok() && json.getAsJsonObject("data").get("transactionId") != null
                && !json.getAsJsonObject("data").get("transactionId").isJsonNull()) {
            json.getAsJsonObject("data").addProperty("transactionId", "tx-fixture");
        }
        pair.add("response", json);
        return pair;
    }

    // ------------------------------------------------------------------
    // the requests

    /**
     * The benchmark's shape: an item with a rule, a GUI with its layers and a rule, and a change to an existing
     * item. Operations 3 and 4 address documents operations 0 and 1 create.
     */
    private static Wire.Request mixed(String id, boolean dryRun) {
        return frame(id, """
                {"pack":"demo","dryRun":%s,"operations":[
                  {"kind":"create","type":"items","id":"ruby_blade","name":"Ruby Blade","edits":[
                    {"field":["ruby_blade","texture"],"value":"ruby"}]},
                  {"kind":"create","type":"guis","id":"shop","name":"Shop"},
                  {"kind":"guiEdit","path":"guis/shop.yml","edits":[
                    {"op":"setScreen","title":"Shop"},
                    {"op":"addLayer","index":0,"type":"panel","id":"frame",
                     "params":{"x":0,"y":0,"width":176,"height":132}}]},
                  {"kind":"logicEdit","path":"items/ruby_blade.yml","edits":[
                    {"op":"addRule","index":0,"rule":{"on":"click","do":[{"give":"gem"}]}}]},
                  {"kind":"logicEdit","path":"guis/shop.yml","edits":[
                    {"op":"addRule","index":0,"rule":{"on":"click","do":[{"give":"coin"}]}}]},
                  {"kind":"setFields","path":"items/sword.yml","rev":1,"edits":[
                    {"field":["sword","texture"],"value":"steel"}]}]}""".formatted(dryRun));
    }

    private static Wire.Request frame(String id, String json) {
        JsonElement parsed = JsonParser.parseString(json);
        return new Wire.Request("authoring.transaction", id, parsed.getAsJsonObject());
    }

    private static JsonObject row(JsonObject report, String path) {
        for (JsonElement element : report.getAsJsonArray("documents")) {
            if (element.getAsJsonObject().get("path").getAsString().equals(path)) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError("no document " + path + " in " + report.get("documents"));
    }

    private static boolean stageRan(JsonObject report, String id) {
        for (JsonElement element : report.getAsJsonArray("stages")) {
            if (element.getAsJsonObject().get("id").getAsString().equals(id)) {
                return element.getAsJsonObject().get("ran").getAsBoolean();
            }
        }
        throw new AssertionError("no stage " + id);
    }

    // ------------------------------------------------------------------
    // the session: the real authoring surfaces over a real pack

    private record Session(RpcDispatcher dispatcher, AuthoringApi authoring, List<String> reloads) {}

    private Path path(String relative) {
        return temp.resolve("packs").resolve(PACK).resolve(relative);
    }

    private Session session() {
        Path packsRoot = temp.resolve("packs");
        DocumentStore store = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        Registry<GuiComponent> components = components();
        List<String> reloads = new ArrayList<>();
        var authoring = new AuthoringApiImpl(store, audit, packsRoot,
                new ModelTransactionService(store, packsRoot, audit), null, () -> null,
                (actor, pack, changed) -> reloads.add(pack), null, List::of,
                base -> new GuiAuthoringApiImpl(base, components, packsRoot, Fresh::new, List.of()),
                base -> new LogicAuthoringApiImpl(base, lookup()));
        authoring.transactionJournal(temp.resolve("transactions"));
        var console = ActorContext.console();
        authoring.createPack(console, PACK);
        authoring.putDocument(console, new AuthoringApi.PutDocumentRequest(PACK, "items/sword.yml", 0, SWORD));
        authoring.putDocument(console, new AuthoringApi.PutDocumentRequest(
                PACK, "items/untouched.yml", 0, UNTOUCHED));
        reloads.clear();
        var dispatcher = new RpcDispatcher();
        TransactionOps.register(dispatcher, authoring,
                attribution -> new ActorContext(attribution, ActorContext.Kind.EDITOR_SESSION,
                        Optional.empty(), node -> true));
        return new Session(dispatcher, authoring, reloads);
    }

    private static Registry<GuiComponent> components() {
        var registry = new RegistryService().registry(RegistryKeys.GUI_COMPONENTS, GuiComponent.class);
        for (GuiComponent component : GuiComponents.builtins()) {
            registry.register(NamespacedId.of("materione", component.name()),
                    component, component.describe(), "materione");
        }
        return registry;
    }

    /** Every file and directory of the packs and the journals, plus the audit log: path and content hash. */
    private List<String> snapshot() {
        List<String> lines = new ArrayList<>();
        for (Path root : List.of(temp.resolve("packs"), temp.resolve("revisions"))) {
            try (var walk = Files.walk(root)) {
                walk.sorted().forEach(file -> {
                    String relative = root.getFileName() + "/" + root.relativize(file).toString().replace('\\', '/');
                    try {
                        lines.add(Files.isDirectory(file) ? "D " + relative
                                : "F " + relative + " " + Documents.sha256(Files.readAllBytes(file)));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Path audit = temp.resolve("audit.log");
        try {
            lines.add(Files.exists(audit) ? "audit " + Documents.sha256(Files.readAllBytes(audit)) : "audit absent");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return lines;
    }

    /** A counter-free allocation view: the GUI background codepoint is the first of its block. */
    private static final class Fresh implements Allocations {
        @Override
        public String allocate(NamespacedId resource, String kind) {
            return "1";
        }

        @Override
        public AllocatedRange range(NamespacedId resource, String kind, int count) {
            return new AllocatedRange(1, count);
        }

        @Override
        public Optional<String> lookup(NamespacedId resource, String kind) {
            return Optional.empty();
        }
    }

    /** The lookup the logic surface resolves steps against: one action (give) and the click trigger. */
    private static LogicLookup lookup() {
        return new LogicLookup() {
            @Override
            public Optional<ActionType<?>> action(String authoredId) {
                return "give".equals(authoredId) ? Optional.<ActionType<?>>of(new Give()) : Optional.empty();
            }

            @Override
            public Optional<ConditionType<?>> condition(String authoredId) {
                return Optional.empty();
            }

            @Override
            public boolean triggerKnown(String authoredId) {
                return authoredId.equals("click");
            }
        };
    }

    /** A plain action that takes one value; the ops only need it to resolve and parse. */
    private static final class Give implements ActionType<String> {
        @Override
        public String id() {
            return "give";
        }

        @Override
        public ActionDescription describe() {
            return new ActionDescription(new SelfDescription("give", "Gives a value",
                    List.of(ParameterSchema.of("value", ParameterType.STRING, false)), "1.0.0"),
                    LogicCategory.DATA, "value");
        }

        @Override
        public Threading threading() {
            return Threading.ANY;
        }

        @Override
        public String parse(ParamReader params) {
            return params.raw("value").map(String::valueOf).orElse("");
        }

        @Override
        public ActionOutcome execute(String params, LogicContext context) {
            return ActionOutcome.OK;
        }
    }

    private static String pretty(JsonObject object) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(Wire.parse(Wire.canonicalJson(object))) + "\n";
    }
}
