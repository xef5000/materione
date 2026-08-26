package dev.materione.web;

import com.google.gson.JsonObject;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;

/**
 * The always-present session ops every transport serves regardless of which
 * content features exist (web.md §4.2 "session" group). Hosts call
 * {@link #register} once when building their dispatcher — this keeps even
 * trivial wire responses out of host code, so the ban rule R8 stays simple:
 * nothing outside {@code dev.materione.web} touches the protocol package.
 */
public final class BaseOps {

    private BaseOps() {}

    /** Registers {@code ping} and {@code session.info}. */
    public static void register(RpcDispatcher dispatcher, String serverVersion) {
        dispatcher.register("ping", request -> {
            JsonObject pong = new JsonObject();
            pong.addProperty("pong", true);
            return Wire.Response.success(request, pong);
        });
        dispatcher.register("session.info", request -> {
            JsonObject info = new JsonObject();
            info.addProperty("serverVersion", serverVersion);
            info.addProperty("protocolVersion", Wire.VERSION);
            return Wire.Response.success(request, info);
        });
    }
}
