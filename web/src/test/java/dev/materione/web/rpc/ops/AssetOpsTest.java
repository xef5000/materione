package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import dev.materione.core.document.AuditLog;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.api.assets.AssetApi;
import dev.materione.api.auth.ActorContext;
import dev.materione.core.authoring.ReloadHook;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for AssetOps: upload, list, get, move, and delete assets,
 * ensuring audit entries and OnePack reload scopes are recorded.
 */
class AssetOpsTest {

    @TempDir
    Path temp;

    @Test
    void assetLifecycleAndReloadQueueing() throws Exception {
        Path packsRoot = temp.resolve("packs");
        Files.createDirectories(packsRoot.resolve("demo"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        CapturingTrigger trigger = new CapturingTrigger();

        RpcDispatcher dispatcher = new RpcDispatcher();
        AssetOps.register(dispatcher, assets(packsRoot, audit, trigger),
                AssetOpsTest::actor);

        // Initially no assets
        Wire.Response listEmpty = dispatcher.dispatch(
                request("assets.list", "1", object("pack", "demo")));
        assertThat(listEmpty.ok()).isTrue();
        assertThat(payload(listEmpty).get("assets").getAsJsonArray()).isEmpty();

        // Put an asset (texture)
        byte[] textureBytes = "sample-png-data".getBytes(StandardCharsets.UTF_8);
        String base64Data = Base64.getEncoder().encodeToString(textureBytes);
        Wire.Response put = dispatcher.dispatch(
                request("assets.put", "2", object("pack", "demo", "path", "assets/textures/ruby.png", "data", base64Data)));
        assertThat(put.ok()).isTrue();
        assertThat(payload(put).get("bytes").getAsInt()).isEqualTo(textureBytes.length);

        // Reload queued with OnePack scope
        assertThat(trigger.scopes).hasSize(1);
        assertThat(trigger.scopes.get(0)).isEqualTo(new ReloadScope.OnePack("demo"));

        // Get the asset back
        Wire.Response get = dispatcher.dispatch(
                request("assets.get", "3", object("pack", "demo", "path", "assets/textures/ruby.png")));
        assertThat(get.ok()).isTrue();
        assertThat(payload(get).get("data").getAsString()).isEqualTo(base64Data);

        // List contains the asset
        Wire.Response list = dispatcher.dispatch(
                request("assets.list", "4", object("pack", "demo")));
        assertThat(list.ok()).isTrue();
        assertThat(payload(list).get("assets").toString()).contains("assets/textures/ruby.png");

        // Move the asset
        Wire.Response move = dispatcher.dispatch(
                request("assets.move", "5", object("pack", "demo", "from", "assets/textures/ruby.png", "to", "assets/textures/ruby_gem.png")));
        assertThat(move.ok()).isTrue();
        assertThat(trigger.scopes).hasSize(2);
        assertThat(trigger.scopes.get(1)).isEqualTo(new ReloadScope.OnePack("demo"));

        // Delete the asset
        Wire.Response delete = dispatcher.dispatch(
                request("assets.delete", "6", object("pack", "demo", "path", "assets/textures/ruby_gem.png")));
        assertThat(delete.ok()).isTrue();
        assertThat(trigger.scopes).hasSize(3);
        assertThat(trigger.scopes.get(2)).isEqualTo(new ReloadScope.OnePack("demo"));

        // Audit log records
        String auditText = Files.readString(temp.resolve("audit.log"));
        assertThat(auditText).contains("assets.put");
        assertThat(auditText).contains("assets.move");
        assertThat(auditText).contains("assets.delete");
    }

    /**
     * The studio shows a model by reading the render the Blockbench workbench
     * cached under `.materione/thumbnails/`. Reads reach it; writes must not,
     * or a free-form upload could rewrite authoring metadata that only
     * `authoring.saveModel` is allowed to produce.
     */
    @Test
    void readsCachedModelRendersButRefusesToWriteThem() throws Exception {
        Path packsRoot = temp.resolve("packs");
        Path thumbnail = packsRoot.resolve("demo/.materione/thumbnails/gem.png");
        Files.createDirectories(thumbnail.getParent());
        byte[] bytes = "render".getBytes(StandardCharsets.UTF_8);
        Files.write(thumbnail, bytes);
        RpcDispatcher dispatcher = new RpcDispatcher();
        AssetOps.register(
                dispatcher,
                assets(packsRoot, new AuditLog(temp.resolve("audit.log")),
                        new CapturingTrigger()),
                AssetOpsTest::actor);

        Wire.Response get = dispatcher.dispatch(request("assets.get", "1",
                object("pack", "demo", "path", ".materione/thumbnails/gem.png")));
        assertThat(get.ok()).isTrue();
        assertThat(payload(get).get("data").getAsString())
                .isEqualTo(Base64.getEncoder().encodeToString(bytes));

        Wire.Response put = dispatcher.dispatch(request("assets.put", "2", object(
                "pack", "demo",
                "path", ".materione/thumbnails/gem.png",
                "data", Base64.getEncoder().encodeToString("forged".getBytes(StandardCharsets.UTF_8)))));
        assertThat(put.ok()).isFalse();

        Wire.Response escape = dispatcher.dispatch(request("assets.get", "3",
                object("pack", "demo", "path", ".materione/thumbnails/../model-links.json")));
        assertThat(escape.ok()).isFalse();
        assertThat(Files.readAllBytes(thumbnail)).isEqualTo(bytes);
    }

    /** The real asset api over the store; mutations reload the pack (D6). */
    private AssetApi assets(Path packsRoot, AuditLog audit, CapturingTrigger trigger) {
        return new dev.materione.core.assets.AssetApiImpl(
                new dev.materione.core.document.AssetStore(packsRoot, audit),
                packsRoot, trigger);
    }

    private static ActorContext actor(String attribution) {
        return new ActorContext(attribution, ActorContext.Kind.EDITOR_SESSION,
                java.util.Optional.empty(), node -> true);
    }

    /** Records the pack-wide preview reloads the api fires after mutations. */
    private static final class CapturingTrigger implements ReloadHook {
        final List<ReloadScope> scopes = new ArrayList<>();
        final List<Path> files = new ArrayList<>();

        @Override
        public void afterChange(ActorContext actor, String pack, Path changed) {
            if (java.nio.file.Files.isDirectory(changed)) {
                scopes.add(new ReloadScope.OnePack(pack));
            } else {
                files.add(changed);
            }
        }
    }

    private static Wire.Request request(String op, String id, JsonObject payload) {
        return new Wire.Request(op, id, payload);
    }

    private static JsonObject payload(Wire.Response response) {
        return response.data().getAsJsonObject();
    }

    private static JsonObject object(String... pairs) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            object.addProperty(pairs[i], pairs[i + 1]);
        }
        return object;
    }
}
