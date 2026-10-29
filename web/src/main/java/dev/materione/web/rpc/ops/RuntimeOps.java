package dev.materione.web.rpc.ops;

import com.google.gson.JsonObject;
import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.runtime.RuntimeApi;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The editor's runtime surface (gui.md §5, §7): previewing a GUI in game for the session's
 * approved player, the player who opened the session in game. This layer never picks a player
 * itself: the host supplies the session's owner, and a session without one (a console session)
 * is refused rather than guessed at.
 *
 * <p>Ops: {@code runtime.openGui} {@code {id}} opens the live GUI for that player and answers
 * {@code {queued}} (true while the window is still opening on the player's thread); and
 * {@code runtime.previewTarget} answers whether that player is online, and their name, so the
 * studio can disable its preview button with a reason.
 */
public final class RuntimeOps {

    private RuntimeOps() {}

    /**
     * @param guis the api's GUI runtime (the only runtime surface the editor reaches, R9)
     * @param actorFor the editor session's actor for an attribution string
     * @param sessionPlayer the approved player of the session that sent the current request
     */
    public static void register(
            RpcDispatcher dispatcher,
            RuntimeApi.GuiRuntimeApi guis,
            Function<String, ActorContext> actorFor,
            Supplier<Optional<UUID>> sessionPlayer) {
        dispatcher.register("runtime.openGui", request -> {
            try {
                NamespacedId id = NamespacedId.parse(requiredString(request.payload(), "id"));
                Optional<UUID> player = sessionPlayer.get();
                if (player.isEmpty()) {
                    return Wire.Response.failure(request, new Wire.Error(
                            DiagnosticCodes.UNRESOLVED_REFERENCE.code(),
                            "this editor session has no approved player to preview for",
                            null,
                            "open the editor in game with /materione editor, then preview from that session"));
                }
                var future = guis.open(actorFor.apply(actorOf(request)), id, player.get());
                // refusals (not live, offline, no permission) complete at once; a window that is
                // opening on the player's thread is still pending, and the reply says so
                boolean settled = future.isDone();
                if (settled) {
                    ApiResult<Void> result = future.join();
                    if (!result.ok()) {
                        return refused(request, result);
                    }
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("queued", !settled);
                return Wire.Response.success(request, payload);
            } catch (IllegalArgumentException e) {
                return Wire.Response.failure(request, new Wire.Error("MTR-0008", e.getMessage(), null, null));
            } catch (Exception e) {
                return Wire.Response.failure(request,
                        new Wire.Error("MTR-0011", String.valueOf(e.getMessage()), null, null));
            }
        });

        dispatcher.register("runtime.previewTarget", request -> {
            Optional<String> name = sessionPlayer.get().flatMap(guis::onlineName);
            JsonObject payload = new JsonObject();
            payload.addProperty("online", name.isPresent());
            name.ifPresent(player -> payload.addProperty("player", player));
            return Wire.Response.success(request, payload);
        });
    }

    // ------------------------------------------------------------------

    /** A refused operation: the first diagnostic's code and message, with its hint. */
    private static Wire.Response refused(Wire.Request request, ApiResult<?> result) {
        Diagnostic diagnostic = result.diagnostics().isEmpty() ? null : result.diagnostics().get(0);
        return Wire.Response.failure(request, new Wire.Error(
                diagnostic == null ? DiagnosticCodes.PIPELINE_STAGE_FAILED.code() : diagnostic.code().code(),
                diagnostic == null ? "the GUI could not be opened" : diagnostic.message(),
                null,
                diagnostic == null ? null : diagnostic.hint()));
    }

    private static String actorOf(Wire.Request request) {
        return "editor:" + request.id();
    }

    private static String requiredString(JsonObject object, String field) {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing field '" + field + "'");
        }
        return value.getAsString();
    }
}
