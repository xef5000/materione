package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.runtime.RuntimeApi;
import dev.materione.testing.golden.GoldenFiles;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The op-level golden for the editor's preview ops ({@code fixtures/protocol/editor-runtime-v1.json}):
 * the exact frames of {@code runtime.openGui} (a window still opening, a refusal, a session with no
 * approved player) and of {@code runtime.previewTarget} (online and offline). The SPA's wrappers must
 * send the request byte for byte and decode the response, so the two sides cannot drift. Regenerate
 * with {@code -Dmaterione.golden.regen=true}.
 */
class EditorRuntimeGoldenTest {

    private static final String FILE_NAME = "editor-runtime-v1.json";
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000c0de");
    private static final String NOT_LIVE =
            "GUI 'demo:shop' is not live: it is unknown, or its last compile quarantined it";

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

    @Test
    void theRecordedFramesAreTheOnesTheDispatcherAnswers() {
        // the golden is built from the live dispatcher, so the frames above are the wire behaviour
        JsonObject built = build();
        assertThat(built.getAsJsonObject("openGui").getAsJsonObject("pending")
                .getAsJsonObject("response").get("ok").getAsBoolean()).isTrue();
        assertThat(built.getAsJsonObject("openGui").getAsJsonObject("refused")
                .getAsJsonObject("response").get("ok").getAsBoolean()).isFalse();
    }

    /** Runs each frame through a real dispatcher and records the request and the reply. */
    private static JsonObject build() {
        var root = new JsonObject();
        var meta = new JsonObject();
        meta.addProperty("file", FILE_NAME);
        meta.addProperty("name", "materione editor runtime op frames");
        meta.addProperty("version", 1);
        root.add("meta", meta);

        var openGui = new JsonObject();
        var pendingGuis = new Fake(new CompletableFuture<>(), Map.of());
        openGui.add("pending", record(
                RuntimeOpsTest.dispatcher(pendingGuis, Optional.of(OWNER)),
                new Wire.Request("runtime.openGui", "rt-open-1", payload("demo:shop"))));
        var refusedGuis = new Fake(CompletableFuture.completedFuture(
                ApiResult.<Void>fail(DiagnosticCodes.UNRESOLVED_REFERENCE, NOT_LIVE)), Map.of());
        openGui.add("refused", record(
                RuntimeOpsTest.dispatcher(refusedGuis, Optional.of(OWNER)),
                new Wire.Request("runtime.openGui", "rt-open-2", payload("demo:shop"))));
        openGui.add("noPlayer", record(
                RuntimeOpsTest.dispatcher(new Fake(new CompletableFuture<>(), Map.of()), Optional.empty()),
                new Wire.Request("runtime.openGui", "rt-open-3", payload("demo:shop"))));
        root.add("openGui", openGui);

        var previewTarget = new JsonObject();
        previewTarget.add("online", record(
                RuntimeOpsTest.dispatcher(new Fake(new CompletableFuture<>(), Map.of(OWNER, "Probe")), Optional.of(OWNER)),
                new Wire.Request("runtime.previewTarget", "rt-target-1", new JsonObject())));
        previewTarget.add("offline", record(
                RuntimeOpsTest.dispatcher(new Fake(new CompletableFuture<>(), Map.of()), Optional.of(OWNER)),
                new Wire.Request("runtime.previewTarget", "rt-target-2", new JsonObject())));
        root.add("previewTarget", previewTarget);
        return root;
    }

    /** The request and the reply the dispatcher gives it, as one golden entry. */
    private static JsonObject record(RpcDispatcher dispatcher, Wire.Request request) {
        Wire.Response response = dispatcher.dispatch(request);
        var entry = new JsonObject();
        entry.add("request", request.toJson());
        entry.add("response", response.toJson());
        return entry;
    }

    private static JsonObject payload(String id) {
        var payload = new JsonObject();
        payload.addProperty("id", id);
        return payload;
    }

    private static String pretty(JsonObject root) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(Wire.parse(Wire.canonicalJson(root))) + "\n";
    }

    /** A scripted GUI api: the open answers with the given future, names come from the map. */
    private record Fake(CompletableFuture<ApiResult<Void>> open, Map<UUID, String> online)
            implements RuntimeApi.GuiRuntimeApi {

        @Override
        public java.util.List<RuntimeApi.GuiView> guis() {
            return java.util.List.of();
        }

        @Override
        public Optional<RuntimeApi.GuiView> gui(NamespacedId id) {
            return Optional.empty();
        }

        @Override
        public CompletableFuture<ApiResult<Void>> open(
                dev.materione.api.auth.ActorContext actor, NamespacedId guiId, UUID targetPlayer) {
            return open;
        }

        @Override
        public Optional<String> onlineName(UUID player) {
            return Optional.ofNullable(online.get(player));
        }
    }
}
