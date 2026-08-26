package dev.materione.web.rpc;

import dev.materione.web.protocol.Wire;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Maps request ops to handlers (web.md §4.2). The dispatcher is transport-
 * agnostic and versioned by negotiation at hello; an unknown op yields a
 * clean error response — never a disconnect — so old editors against newer
 * servers (and vice versa) degrade gracefully.
 *
 * <p>Handlers run on WS/IO threads and must not touch Bukkit state directly;
 * anything world-facing hops through the scheduling gateway in their
 * implementation.
 */
public final class RpcDispatcher {

    /** A single RPC handler: request in, reply out. Thrown errors become error replies. */
    @FunctionalInterface
    public interface Handler {
        Wire.Response handle(Wire.Request request);
    }

    private static final Logger LOG = Logger.getLogger(RpcDispatcher.class.getName());

    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();

    public void register(String op, Handler handler) {
        Handler previous = handlers.putIfAbsent(op, handler);
        if (previous != null) {
            throw new IllegalStateException("RPC op already registered: " + op);
        }
    }

    /** Dispatches one request; never throws. */
    public Wire.Response dispatch(Wire.Request request) {
        Handler handler = handlers.get(request.op());
        if (handler == null) {
            return Wire.Response.failure(request, new Wire.Error(
                    "MTR-0033",
                    "unknown op '" + request.op() + "'",
                    null,
                    "the editor or server may be outdated (protocol "
                            + Wire.VERSION + ")"));
        }
        try {
            return handler.handle(request);
        } catch (Exception e) {
            LOG.warning("rpc handler failed for " + request.op() + ": " + e);
            return Wire.Response.failure(request, new Wire.Error(
                    "MTR-0011", "handler failed: " + e.getMessage(), null, null));
        }
    }
}
