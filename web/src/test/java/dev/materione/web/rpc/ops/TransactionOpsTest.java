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
        root.add("apply", pair(session.dispatcher, mixed("t-2", false)));
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
        return root;
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
