package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.GuiComponentConfiguration;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.registry.RegistryEntry;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.authoring.AuthoringApiImpl;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The web side of {@code authoring.setFields} with binaries: the optional
 * {@code assets} list is decoded, committed with the edits or refused with them,
 * and the {@code authoring.describe} reply carries the gui-components registry.
 */
class EditorFieldAssetsTest {

    private static final String SEED = "shop:\n  title: Shop\n  rows: 3\n  layers: []\n";

    @TempDir
    Path temp;

    private DocumentStore store;
    private RpcDispatcher dispatcher;

    private void open() throws Exception {
        store = new DocumentStore(temp.resolve("packs"), temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        var authoring = new AuthoringApiImpl(store, audit, temp.resolve("packs"),
                new ModelTransactionService(store, temp.resolve("packs"), audit),
                null, () -> null, (actor, pack, changed) -> { });
        dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, authoring, List::of, List::of,
                attribution -> new ActorContext(attribution, ActorContext.Kind.EDITOR_SESSION,
                        Optional.empty(), node -> true),
                dev.materione.api.registry.BehaviorVocabulary::empty,
                () -> List.of(entry()));
        dispatcher.dispatch(request("edit.createPack", "1", object("pack", "demo")));
        dispatcher.dispatch(request("edit.put", "2", object(
                "pack", "demo", "path", "guis/shop.yml", "rev", "0", "content", SEED)));
    }

    @Test
    void assetsCommitWithTheEditsAndDecodeFromBase64() throws Exception {
        open();
        Wire.Response saved = dispatcher.dispatch(save("3", 1, "assets/textures/gui/shop/art.png", "png-bytes"));

        assertThat(saved.ok()).isTrue();
        assertThat(saved.data().getAsJsonObject().get("rev").getAsInt()).isEqualTo(2);
        assertThat(saved.data().getAsJsonObject().get("raw").getAsString()).contains("title: Market");
        assertThat(Files.readString(temp.resolve("packs/demo/assets/textures/gui/shop/art.png")))
                .isEqualTo("png-bytes");
    }

    @Test
    void aStaleSaveWithAssetsWritesNeitherEditNorAsset() throws Exception {
        open();
        Wire.Response refused = dispatcher.dispatch(save("3", 0, "assets/textures/gui/shop/art.png", "png"));

        assertThat(refused.ok()).isFalse();
        assertThat(refused.error().code()).isEqualTo("MTR-0034");
        assertThat(Files.exists(temp.resolve("packs/demo/assets/textures/gui/shop/art.png"))).isFalse();
        assertThat(store.read("demo", "guis/shop.yml").raw()).contains("title: Shop");
    }

    @Test
    void anAssetOutsideTheShippedTreeIsRefusedBeforeAnyWrite() throws Exception {
        open();
        Wire.Response refused = dispatcher.dispatch(save("3", 1, ".materione/thumbnails/shop.png", "png"));

        assertThat(refused.ok()).isFalse();
        assertThat(refused.error().code()).isEqualTo("MTR-0008");
        assertThat(store.read("demo", "guis/shop.yml").revision()).isEqualTo(1);
    }

    @Test
    void malformedAssetDataIsABadRequestNotAHalfWrite() throws Exception {
        open();
        JsonObject body = saveBody("3", 1);
        var asset = new JsonObject();
        asset.addProperty("path", "assets/textures/gui/shop/art.png");
        asset.addProperty("data", "%%% not base64 %%%");
        var assets = new JsonArray();
        assets.add(asset);
        body.add("assets", assets);

        Wire.Response refused = dispatcher.dispatch(request("authoring.setFields", "4", body));
        assertThat(refused.ok()).isFalse();
        assertThat(refused.error().code()).isEqualTo("MTR-0008");
        assertThat(store.read("demo", "guis/shop.yml").revision()).isEqualTo(1);
    }

    @Test
    void assetsMustBeAnArrayOfPathAndData() throws Exception {
        open();
        JsonObject notAnArray = saveBody("3", 1);
        notAnArray.addProperty("assets", "oops");
        assertThat(dispatcher.dispatch(request("authoring.setFields", "4", notAnArray)).ok()).isFalse();

        JsonObject missingData = saveBody("5", 1);
        var entry = new JsonObject();
        entry.addProperty("path", "assets/textures/gui/x.png");
        var entries = new JsonArray();
        entries.add(entry);
        missingData.add("assets", entries);
        assertThat(dispatcher.dispatch(request("authoring.setFields", "6", missingData)).ok()).isFalse();
    }

    @Test
    void aPixelOnlySaveTakesARevisionWithoutEditingTheYaml() throws Exception {
        open();
        Wire.Response saved = dispatcher.dispatch(request("authoring.setFields", "3",
                pixelOnlyBody(1, "assets/textures/gui/shop/art.png", "png-bytes")));

        assertThat(saved.ok()).isTrue();
        assertThat(saved.data().getAsJsonObject().get("rev").getAsInt()).isEqualTo(2);
        assertThat(saved.data().getAsJsonObject().get("raw").getAsString()).isEqualTo(SEED);
        assertThat(Files.readString(temp.resolve("packs/demo/assets/textures/gui/shop/art.png")))
                .isEqualTo("png-bytes");
    }

    @Test
    void aStalePixelOnlySaveIsConflictedAndWritesNothing() throws Exception {
        open();
        Wire.Response refused = dispatcher.dispatch(request("authoring.setFields", "3",
                pixelOnlyBody(0, "assets/textures/gui/shop/art.png", "png")));

        assertThat(refused.ok()).isFalse();
        assertThat(refused.error().code()).isEqualTo("MTR-0034");
        assertThat(Files.exists(temp.resolve("packs/demo/assets/textures/gui/shop/art.png"))).isFalse();
        assertThat(store.read("demo", "guis/shop.yml").revision()).isEqualTo(1);
    }

    @Test
    void aSaveWithNoEditsAndNoAssetsIsStillRejected() throws Exception {
        open();
        JsonObject empty = saveBody("3", 1);
        empty.add("edits", new JsonArray());

        Wire.Response refused = dispatcher.dispatch(request("authoring.setFields", "4", empty));
        assertThat(refused.ok()).isFalse();
        assertThat(refused.error().code()).isEqualTo("MTR-0008");
        assertThat(store.read("demo", "guis/shop.yml").revision()).isEqualTo(1);
    }

    /** A paint-only studio save: an empty edits list and one binary. */
    private static JsonObject pixelOnlyBody(int revision, String assetPath, String data) {
        JsonObject body = object("pack", "demo", "path", "guis/shop.yml");
        body.addProperty("rev", revision);
        body.add("edits", new JsonArray());
        var asset = new JsonObject();
        asset.addProperty("path", assetPath);
        asset.addProperty("data", Base64.getEncoder().encodeToString(data.getBytes(
                java.nio.charset.StandardCharsets.UTF_8)));
        var assets = new JsonArray();
        assets.add(asset);
        body.add("assets", assets);
        return body;
    }

    @Test
    void describeCarriesTheGuiComponentRegistryWithItsTarget() throws Exception {
        open();
        Wire.Response described = dispatcher.dispatch(request("authoring.describe", "7", new JsonObject()));

        assertThat(described.ok()).isTrue();
        JsonArray components = described.data().getAsJsonObject().getAsJsonArray("guiComponents");
        assertThat(components).hasSize(1);
        JsonObject panel = components.get(0).getAsJsonObject();
        assertThat(panel.get("id").getAsString()).isEqualTo("materione:panel");
        assertThat(panel.get("displayName").getAsString()).isEqualTo("Panel");
        assertThat(panel.get("target").getAsString()).isEqualTo("raster");
        assertThat(panel.get("registeredBy").getAsString()).isEqualTo("materione");
    }

    // ------------------------------------------------------------------

    private static RegistryEntry<?> entry() {
        var panel = new GuiComponent() {
            @Override
            public String name() {
                return "panel";
            }

            @Override
            public SelfDescription describe() {
                return SelfDescription.of("Panel", "A raised frame");
            }

            @Override
            public RenderTarget target() {
                return RenderTarget.RASTER;
            }

            @Override
            public CompiledGuiLayer parse(GuiComponentConfiguration configuration) {
                throw new UnsupportedOperationException("not parsed in this test");
            }
        };
        return new RegistryEntry<>(NamespacedId.parse("materione:panel"), panel, panel.describe(), "materione");
    }

    /** A studio save with one title edit and one binary at the given path. */
    private static Wire.Request save(String id, int revision, String assetPath, String data) {
        JsonObject body = saveBody(id, revision);
        var asset = new JsonObject();
        asset.addProperty("path", assetPath);
        asset.addProperty("data", Base64.getEncoder().encodeToString(data.getBytes(
                java.nio.charset.StandardCharsets.UTF_8)));
        var assets = new JsonArray();
        assets.add(asset);
        body.add("assets", assets);
        return request("authoring.setFields", id, body);
    }

    private static JsonObject saveBody(String id, int revision) {
        var edit = new JsonObject();
        var field = new JsonArray();
        field.add("shop");
        field.add("title");
        edit.add("field", field);
        edit.addProperty("value", "Market");
        var edits = new JsonArray();
        edits.add(edit);
        JsonObject body = object("pack", "demo", "path", "guis/shop.yml");
        body.addProperty("rev", revision);
        body.add("edits", edits);
        return body;
    }

    private static Wire.Request request(String op, String id, JsonObject payload) {
        return new Wire.Request(op, id, payload);
    }

    private static JsonObject object(String... pairs) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            object.addProperty(pairs[i], pairs[i + 1]);
        }
        return object;
    }
}
