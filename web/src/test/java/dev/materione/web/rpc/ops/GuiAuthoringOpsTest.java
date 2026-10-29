package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.content.AllocatedRange;
import dev.materione.api.content.Allocations;
import dev.materione.api.gui.GuiAuthoringApi;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.content.gui.GuiAuthoringApiImpl;
import dev.materione.content.gui.GuiComponents;
import dev.materione.core.authoring.AuthoringApiImpl;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import dev.materione.core.registry.RegistryService;
import dev.materione.testing.golden.GoldenFiles;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The GUI ops ({@code gui.get}, {@code gui.edit}, {@code gui.customizeItem}, {@code gui.render},
 * {@code gui.validate}) through the real dispatcher over a real pack, and the frames they exchange
 * pinned in {@code fixtures/protocol/editor-gui-v1.json}. The SPA's request builders must produce
 * the request frames byte for byte. Regenerate with {@code -Dmaterione.golden.regen=true}.
 */
class GuiAuthoringOpsTest {

    private static final String FILE_NAME = "editor-gui-v1.json";
    private static final String PACK = "demo";
    private static final String PATH = "guis/main.yml";
    private static final String SEED = """
            main:
              title: Shop
              rows: 3
              layers:
                - id: frame
                  type: panel
                  x: 0
                  y: 0
                  width: 176
                  height: 132
                - id: sword
                  type: item
                  slot: 13
                  item: demo:sword
                  amount: 1
            """;

    @TempDir
    Path temp;

    @Test
    void theOpsAnswerLikeTheApi() throws IOException {
        Session session = session();
        Wire.Response get = session.dispatcher.dispatch(get("g-1"));
        assertThat(get.ok()).isTrue();
        assertThat(get.data().getAsJsonObject().getAsJsonArray("layers")).hasSize(2);

        Wire.Response edited = session.dispatcher.dispatch(edit("e-1", 1));
        assertThat(edited.ok()).as(String.valueOf(edited.error())).isTrue();
        assertThat(edited.data().getAsJsonObject().get("rev").getAsInt()).isEqualTo(2);

        Wire.Response stale = session.dispatcher.dispatch(edit("e-2", 1));
        assertThat(stale.ok()).isFalse();
        assertThat(stale.error().code()).isEqualTo("MTR-0034");

        Wire.Response customized = session.dispatcher.dispatch(customize("c-1", 2));
        assertThat(customized.ok()).as(String.valueOf(customized.error())).isTrue();
        assertThat(customized.data().getAsJsonObject().get("id").getAsString()).isEqualTo("main_sword");
        assertThat(customized.data().getAsJsonObject().get("rev").getAsInt()).isEqualTo(3);

        Wire.Response rendered = session.dispatcher.dispatch(render("r-1"));
        assertThat(rendered.ok()).as(String.valueOf(rendered.error())).isTrue();
        assertThat(rendered.data().getAsJsonObject().get("width").getAsInt()).isEqualTo(176);
        assertThat(rendered.data().getAsJsonObject().get("background").getAsString()).isNotBlank();

        Wire.Response validated = session.dispatcher.dispatch(validateDraft("v-1"));
        assertThat(validated.ok()).as(String.valueOf(validated.error())).isTrue();
        assertThat(validated.data().getAsJsonObject().getAsJsonArray("diagnostics")).hasSize(1);
        assertThat(validated.data().getAsJsonObject().getAsJsonArray("diagnostics").get(0).getAsJsonObject()
                .get("code").getAsString()).isEqualTo("MTR-0052");
    }

    @Test
    void aMalformedRequestIsABadRequestNotAFailure() {
        Session session = session();
        Wire.Response response = session.dispatcher.dispatch(new Wire.Request("gui.render", "bad-1",
                with("source", new JsonObject())));
        assertThat(response.ok()).isFalse();
        assertThat(response.error().code()).isEqualTo("MTR-0008");
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
                "missing " + target + " — regenerate once with -Dmaterione.golden.regen=true");
        assertThat(pretty(built)).isEqualTo(Files.readString(target, StandardCharsets.UTF_8));
    }

    /** Runs each frame through the real dispatcher in a fixed order, and records the pairs. */
    private JsonObject build() {
        Session session = session();
        var root = new JsonObject();
        var meta = new JsonObject();
        meta.addProperty("file", FILE_NAME);
        meta.addProperty("name", "materione GUI op frames");
        meta.addProperty("version", 1);
        root.add("meta", meta);
        root.add("get", pair(session.dispatcher, get("g-1")));
        root.add("edit", pair(session.dispatcher, edit("e-1", 1)));
        root.add("editConflict", pair(session.dispatcher, edit("e-2", 1)));
        root.add("customizeItem", pair(session.dispatcher, customize("c-1", 2)));
        // the background's PNG bytes depend on the JDK's deflate; its pixels are pinned by the raster
        // vectors and the content parity test, so the frame keeps the reply's shape without the bytes
        JsonObject rendered = pair(session.dispatcher, render("r-1"));
        rendered.getAsJsonObject("response").getAsJsonObject("data").remove("background");
        root.add("render", rendered);
        root.add("validate", pair(session.dispatcher, validateDraft("v-1")));
        return root;
    }

    private static JsonObject pair(RpcDispatcher dispatcher, Wire.Request request) {
        Wire.Response response = dispatcher.dispatch(request);
        var pair = new JsonObject();
        pair.add("request", request.toJson());
        pair.add("response", response.toJson());
        return pair;
    }

    private static Wire.Request get(String id) {
        return new Wire.Request("gui.get", id, object("pack", PACK, "path", PATH));
    }

    /** One layer edit at {@code rev}: a new panel, and the sword's amount. */
    private static Wire.Request edit(String id, int rev) {
        var add = new JsonObject();
        add.addProperty("op", "addLayer");
        add.addProperty("index", 0);
        add.addProperty("type", "panel");
        add.addProperty("id", "backdrop");
        var params = new JsonObject();
        params.addProperty("x", 4);
        params.addProperty("y", 4);
        add.add("params", params);
        var patch = new JsonObject();
        patch.addProperty("op", "patchLayer");
        patch.addProperty("id", "sword");
        var set = new JsonObject();
        set.addProperty("amount", 2);
        patch.add("set", set);
        var edits = new JsonArray();
        edits.add(add);
        edits.add(patch);
        JsonObject payload = object("pack", PACK, "path", PATH);
        payload.addProperty("rev", rev);
        payload.add("edits", edits);
        payload.add("assets", new JsonArray());
        return new Wire.Request("gui.edit", id, payload);
    }

    private static Wire.Request customize(String id, int rev) {
        JsonObject payload = object("pack", PACK, "path", PATH);
        payload.addProperty("rev", rev);
        payload.addProperty("layer", "sword");
        return new Wire.Request("gui.customizeItem", id, payload);
    }

    private static Wire.Request render(String id) {
        var source = new JsonObject();
        source.addProperty("kind", "saved");
        source.addProperty("pack", PACK);
        source.addProperty("path", PATH);
        return new Wire.Request("gui.render", id, with("source", source));
    }

    /** A draft with a text layer the compiler skips (a reserved character). */
    private static Wire.Request validateDraft(String id) {
        var layer = new JsonObject();
        layer.addProperty("id", "heading");
        layer.addProperty("type", "text");
        var parameters = new JsonObject();
        parameters.addProperty("text", "Shop <b>");
        parameters.addProperty("x", 8);
        parameters.addProperty("y", 6);
        layer.add("parameters", parameters);
        var layers = new JsonArray();
        layers.add(layer);
        var document = new JsonObject();
        document.addProperty("pack", PACK);
        document.addProperty("id", "main");
        document.addProperty("title", "Shop");
        document.addProperty("rows", 1);
        document.add("layers", layers);
        var source = new JsonObject();
        source.addProperty("kind", "draft");
        source.add("document", document);
        return new Wire.Request("gui.validate", id, with("source", source));
    }

    private record Session(RpcDispatcher dispatcher) {}

    private Session session() {
        Path packsRoot = temp.resolve("packs");
        DocumentStore store = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        Registry<GuiComponent> components = components();
        var authoring = new AuthoringApiImpl(store, audit, packsRoot,
                new ModelTransactionService(store, packsRoot, audit), null, () -> null, (a, p, c) -> { },
                null, List::of,
                base -> new GuiAuthoringApiImpl(base, components, packsRoot, Fresh::new, List.of()));
        var actor = ActorContext.console();
        authoring.createPack(actor, PACK);
        authoring.createDocument(actor, new AuthoringApi.CreateDocumentRequest(
                PACK, PATH, "main", "Main", SEED));
        authoring.createDocument(actor, new AuthoringApi.CreateDocumentRequest(
                PACK, "items/sword.yml", "sword", "Sword", null));
        var dispatcher = new RpcDispatcher();
        GuiAuthoringOps.register(dispatcher, authoring.gui(),
                attribution -> new ActorContext(attribution, ActorContext.Kind.EDITOR_SESSION,
                        Optional.empty(), node -> true));
        return new Session(dispatcher);
    }

    private static Registry<GuiComponent> components() {
        var registry = new RegistryService().registry(RegistryKeys.GUI_COMPONENTS, GuiComponent.class);
        for (GuiComponent component : GuiComponents.builtins()) {
            registry.register(NamespacedId.of("materione", component.name()),
                    component, component.describe(), "materione");
        }
        return registry;
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

    /** A payload with one nested value. */
    private static JsonObject with(String key, JsonObject value) {
        JsonObject object = new JsonObject();
        object.add(key, value);
        return object;
    }

    private static JsonObject object(String... pairs) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            object.addProperty(pairs[i], pairs[i + 1]);
        }
        return object;
    }

    private static String pretty(JsonObject root) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(Wire.parse(Wire.canonicalJson(root))) + "\n";
    }
}
