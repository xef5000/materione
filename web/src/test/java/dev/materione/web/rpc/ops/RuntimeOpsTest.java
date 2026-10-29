package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.runtime.RuntimeApi;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The editor's preview ops (gui.md §7): the approved player is the session's own, a session
 * without one is refused, and a refusal from the api reaches the editor with its code.
 */
class RuntimeOpsTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000c0de");

    @Test
    void aSessionWithoutAnApprovedPlayerIsRefusedNotGuessedFor() {
        var gui = new FakeGuis(() -> CompletableFuture.completedFuture(ApiResult.ok(null)), Map.of());
        var dispatcher = dispatcher(gui, Optional.empty());

        Wire.Response response = dispatcher.dispatch(request("runtime.openGui", object("id", "demo:shop")));

        assertThat(response.ok()).isFalse();
        assertThat(response.error().code()).isEqualTo(DiagnosticCodes.UNRESOLVED_REFERENCE.code());
        assertThat(gui.opened).isEmpty();
    }

    @Test
    void aRefusedOpenReachesTheEditorWithItsCodeAndMessage() {
        var refused = CompletableFuture.completedFuture(ApiResult.<Void>fail(
                DiagnosticCodes.UNRESOLVED_REFERENCE, "GUI 'demo:shop' is not live"));
        var gui = new FakeGuis(() -> refused, Map.of());

        Wire.Response response = dispatcher(gui, Optional.of(OWNER))
                .dispatch(request("runtime.openGui", object("id", "demo:shop")));

        assertThat(response.ok()).isFalse();
        assertThat(response.error().code()).isEqualTo("MTR-0009");
        assertThat(response.error().message()).contains("not live");
        assertThat(gui.opened).containsExactly(OWNER + " demo:shop");
    }

    @Test
    void aWindowStillOpeningOnThePlayersThreadIsAcknowledgedAsQueued() {
        var pending = new CompletableFuture<ApiResult<Void>>();
        var gui = new FakeGuis(() -> pending, Map.of());

        Wire.Response response = dispatcher(gui, Optional.of(OWNER))
                .dispatch(request("runtime.openGui", object("id", "demo:shop")));

        assertThat(response.ok()).isTrue();
        assertThat(response.data().getAsJsonObject().get("queued").getAsBoolean()).isTrue();
    }

    @Test
    void anOpenThatAlreadyCompletedIsNotQueued() {
        var done = CompletableFuture.completedFuture(ApiResult.<Void>ok(null));

        Wire.Response response = dispatcher(new FakeGuis(() -> done, Map.of()), Optional.of(OWNER))
                .dispatch(request("runtime.openGui", object("id", "demo:shop")));

        assertThat(response.ok()).isTrue();
        assertThat(response.data().getAsJsonObject().get("queued").getAsBoolean()).isFalse();
    }

    @Test
    void aMalformedIdIsABadRequest() {
        var gui = new FakeGuis(() -> CompletableFuture.completedFuture(ApiResult.ok(null)), Map.of());

        Wire.Response response = dispatcher(gui, Optional.of(OWNER))
                .dispatch(request("runtime.openGui", object("id", "nocolon")));

        assertThat(response.ok()).isFalse();
        assertThat(response.error().code()).isEqualTo("MTR-0008");
        assertThat(gui.opened).isEmpty();
    }

    @Test
    void previewTargetReportsTheApprovedPlayerWhenOnline() {
        var gui = new FakeGuis(() -> CompletableFuture.completedFuture(ApiResult.ok(null)),
                Map.of(OWNER, "Probe"));

        Wire.Response response = dispatcher(gui, Optional.of(OWNER))
                .dispatch(request("runtime.previewTarget", new JsonObject()));

        assertThat(response.ok()).isTrue();
        assertThat(response.data().getAsJsonObject().get("online").getAsBoolean()).isTrue();
        assertThat(response.data().getAsJsonObject().get("player").getAsString()).isEqualTo("Probe");
    }

    @Test
    void previewTargetIsOfflineWithoutAnApprovedPlayer() {
        var gui = new FakeGuis(() -> CompletableFuture.completedFuture(ApiResult.ok(null)), Map.of());

        Wire.Response response = dispatcher(gui, Optional.empty())
                .dispatch(request("runtime.previewTarget", new JsonObject()));

        assertThat(response.ok()).isTrue();
        assertThat(response.data().getAsJsonObject().get("online").getAsBoolean()).isFalse();
        assertThat(response.data().getAsJsonObject().has("player")).isFalse();
    }

    // --- fixtures -----------------------------------------------------------

    static RpcDispatcher dispatcher(RuntimeApi.GuiRuntimeApi gui, Optional<UUID> owner) {
        var dispatcher = new RpcDispatcher();
        RuntimeOps.register(dispatcher, gui, attribution -> ActorContext.console(), () -> owner);
        return dispatcher;
    }

    static Wire.Request request(String op, JsonObject payload) {
        return new Wire.Request(op, "rt-" + op, payload);
    }

    static JsonObject object(String... pairs) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            object.addProperty(pairs[i], pairs[i + 1]);
        }
        return object;
    }

    /** A GUI api that records opens and answers with a scripted outcome. */
    static final class FakeGuis implements RuntimeApi.GuiRuntimeApi {

        private final Supplier<CompletableFuture<ApiResult<Void>>> outcome;
        private final Map<UUID, String> online;
        final List<String> opened = new java.util.ArrayList<>();

        FakeGuis(Supplier<CompletableFuture<ApiResult<Void>>> outcome, Map<UUID, String> online) {
            this.outcome = outcome;
            this.online = online;
        }

        @Override
        public List<RuntimeApi.GuiView> guis() {
            return List.of();
        }

        @Override
        public Optional<RuntimeApi.GuiView> gui(NamespacedId id) {
            return Optional.empty();
        }

        @Override
        public CompletableFuture<ApiResult<Void>> open(ActorContext actor, NamespacedId guiId, UUID targetPlayer) {
            opened.add(targetPlayer + " " + guiId);
            return outcome.get();
        }

        @Override
        public Optional<String> onlineName(UUID player) {
            return Optional.ofNullable(online.get(player));
        }
    }
}
