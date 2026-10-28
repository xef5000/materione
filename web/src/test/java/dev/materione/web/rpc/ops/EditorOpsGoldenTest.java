package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.materione.api.auth.ActorContext;
import dev.materione.core.authoring.AuthoringApiImpl;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
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
 * The op-level golden ({@code fixtures/protocol/editor-ops-v1.json}): the exact
 * frames of {@code authoring.setFields} carrying a paint asset, and of the same
 * save after its revision went stale. The SPA's wrapper must produce the request
 * byte for byte, and the JVM must produce the response byte for byte, so the two
 * sides cannot drift. Regenerate with {@code -Dmaterione.golden.regen=true}.
 */
class EditorOpsGoldenTest {

    private static final String FILE_NAME = "editor-ops-v1.json";
    private static final String PACK = "demo";
    private static final String PATH = "guis/shop.yml";
    private static final String SEED = """
            shop:
              title: Shop
              rows: 3
              layers:
                - id: frame
                  type: panel
                  width: 176
                  height: 168
            """;
    /** The eight-byte PNG signature stands in for a canvas-sized paint file. */
    private static final byte[] PAINT = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @TempDir
    Path temp;

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

    /** Runs the two frames through the real dispatcher over a seeded pack, and records them. */
    private JsonObject build() throws IOException {
        DocumentStore store = new DocumentStore(temp.resolve("packs"), temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        var authoring = new AuthoringApiImpl(store, audit, temp.resolve("packs"),
                new ModelTransactionService(store, temp.resolve("packs"), audit),
                null, () -> null, (actor, pack, changed) -> { });
        var dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, authoring, List::of, List::of,
                attribution -> new ActorContext(attribution, ActorContext.Kind.EDITOR_SESSION,
                        Optional.empty(), node -> true));

        dispatcher.dispatch(new Wire.Request("edit.createPack", "seed-1", object("pack", PACK)));
        dispatcher.dispatch(new Wire.Request("edit.put", "seed-2", object(
                "pack", PACK, "path", PATH, "rev", "0", "content", SEED)));

        Wire.Request save = saveFrame("ops-1", 1);
        Wire.Response saved = dispatcher.dispatch(save);
        assertThat(saved.ok()).isTrue();
        // the same save with its revision now stale: refused, and nothing it carried is written
        Wire.Request stale = saveFrame("ops-2", 1);
        Wire.Response refused = dispatcher.dispatch(stale);
        assertThat(refused.ok()).isFalse();

        var root = new JsonObject();
        var meta = new JsonObject();
        meta.addProperty("file", FILE_NAME);
        meta.addProperty("name", "materione editor op frames");
        meta.addProperty("version", 1);
        root.add("meta", meta);
        var setFields = new JsonObject();
        setFields.add("request", save.toJson());
        setFields.add("response", saved.toJson());
        var conflict = new JsonObject();
        conflict.add("request", stale.toJson());
        conflict.add("response", refused.toJson());
        setFields.add("conflict", conflict);
        root.add("setFields", setFields);
        return root;
    }

    /** A studio save: one title edit and one paint asset at {@code assets/}. */
    private static Wire.Request saveFrame(String id, int revision) {
        var edit = new JsonObject();
        var field = new JsonArray();
        field.add("shop");
        field.add("title");
        edit.add("field", field);
        edit.addProperty("value", "Market");
        var edits = new JsonArray();
        edits.add(edit);
        var asset = new JsonObject();
        asset.addProperty("path", "assets/textures/gui/shop/art.png");
        asset.addProperty("data", java.util.Base64.getEncoder().encodeToString(PAINT));
        var assets = new JsonArray();
        assets.add(asset);
        JsonObject payload = object("pack", PACK, "path", PATH);
        payload.addProperty("rev", revision); // a JSON number, as the SPA sends it
        payload.add("edits", edits);
        payload.add("assets", assets);
        return new Wire.Request("authoring.setFields", id, payload);
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
