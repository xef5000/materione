package dev.materione.web;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.util.StatusCodes;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The embedded direct-mode listener (web.md §4.4): one loopback port serving
 * the editor SPA (classpath statics under {@code webui/}), the session-blob
 * capability route ({@code /s/<code>}), and the WebSocket channel
 * ({@code /editor/ws}). Loopback binding is a convenience, not the auth —
 * every socket still completes the signed handshake and in-game trust.
 *
 * <p>Owns nothing content-related: RPC handlers are registered by the host
 * into a {@link RpcDispatcher} handed in at construction (kept in
 * {@link EditorSockets}). Fail-soft: bind failures walk a few nearby ports
 * before giving up with MTR-0030, and a non-loopback bind warns loudly
 * (MTR-0036) instead of silently exposing.
 */
public final class EditorListener {

    /** How many consecutive ports to try past the configured one. */
    private static final int PORT_FALLBACK = 8;

    private final EditorSessions sessions;
    private final EditorSockets sockets;
    private final RpcDispatcher dispatcher;
    private final DiagnosticsBus diagnostics;

    private Undertow server;
    private int boundPort = -1;
    private String boundHost = "";

    public EditorListener(
            EditorSessions sessions,
            EditorSockets sockets,
            RpcDispatcher dispatcher,
            DiagnosticsBus diagnostics) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.sockets = Objects.requireNonNull(sockets, "sockets");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** Starts the HTTP+WS listener; returns false when no port could be bound. */
    public boolean start(EditorConfig config) {
        if (!config.loopbackOnly()) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.EDITOR_BIND_EXPOSED,
                    dev.materione.api.diagnostics.Severity.WARN,
                    "editor listener binds " + config.host() + " — reachable beyond this machine",
                    null, null,
                    "keep editor_host at 127.0.0.1; remote teams should use a TLS reverse proxy "
                            + "(plain HTTP beyond loopback also disables browser crypto)"));
        }
        for (int candidate = config.port(); candidate <= config.port() + PORT_FALLBACK; candidate++) {
            try {
                boot(config.host(), candidate);
                if (candidate != config.port()) {
                    diagnostics.publish(new Diagnostic(
                            DiagnosticCodes.EDITOR_LISTENER_FAILED,
                            dev.materione.api.diagnostics.Severity.WARN,
                            "configured editor port " + config.port() + " was busy; bound to "
                                    + candidate + " instead",
                            null, null, "set editor_port in settings.yml to pin a port"));
                }
                return true;
            } catch (RuntimeException e) {
                server = null;
                if (!(rootCause(e) instanceof java.net.BindException)) {
                    diagnostics.publish(bindFailure(config, candidate, e));
                    return false;
                }
            }
        }
        diagnostics.publish(bindFailure(config, config.port(),
                new java.net.BindException("no free port in range")));
        return false;
    }

    /** Creates a session and composes its full capability URL. */
    public String openSessionUrl(String advertiseHost) {
        return openSessionUrl(advertiseHost, "");
    }

    /** Opens a session bound to the initiating player, if any. */
    public String openSessionUrl(String advertiseHost, String ownerId) {
        EditorSession session = sessions.create(ownerId);
        String host = advertiseHost == null || advertiseHost.isBlank()
                ? (boundHost.equals("0.0.0.0") ? "127.0.0.1" : boundHost)
                : advertiseHost;
        return "http://" + host + ":" + boundPort + "/?s=" + session.sessionId()
                + "#" + session.wrappingKeyFragment();
    }

    public boolean isRunning() {
        return server != null;
    }

    public int boundPort() {
        return boundPort;
    }

    /** The host the listener actually bound (for the relay blob's LAN hint). */
    public String boundHost() {
        return boundHost;
    }

    public int activeSessions() {
        return sessions.active().size();
    }

    public void stop() {
        if (server != null) {
            try {
                sessions.closeAll();
                server.stop();
            } catch (Exception e) {
                diagnostics.publish(new Diagnostic(
                        DiagnosticCodes.EDITOR_LISTENER_FAILED,
                        dev.materione.api.diagnostics.Severity.WARN,
                        "listener stop problem: " + e.getMessage(), null, null, null));
            }
            server = null;
            boundPort = -1;
        }
    }

    // ------------------------------------------------------------------

    private void boot(String host, int port) {
        RoutingHandler routes = Handlers.routing()
                .get("/editor/ws", Handlers.websocket(onUpgrade()))
                .get("/s/{code}", this::serveBlob)
                .setFallbackHandler(this::serveStatic);
        Undertow undertowServer = Undertow.builder()
                .addHttpListener(port, host)
                .setHandler(routes)
                .build();
        undertowServer.start();

        this.server = undertowServer;
        this.boundPort = port;
        this.boundHost = host;
    }

    private WebSocketConnectionCallback onUpgrade() {
        return new WebSocketConnectionCallback() {
            @Override
            public void onConnect(WebSocketHttpExchange exchange, io.undertow.websockets.core.WebSocketChannel channel) {
                sockets.attach(channel);
            }
        };
    }

    /** GET /s/{code} → the sealed session blob (capability URL half). */
    private void serveBlob(HttpServerExchange exchange) {
        var codes = exchange.getQueryParameters().get("code");
        String code = codes == null || codes.isEmpty() ? "" : codes.peek();
        EditorSession session = code.isEmpty() ? null : sessions.find(code);
        // A uniform 404 for unknown AND closed codes: no existence oracle.
        if (session == null || session.isClosed()) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            exchange.endExchange();
            return;
        }
        byte[] body = Wire.canonicalBytes(session.sealedBlob());
        exchange.getResponseHeaders().put(io.undertow.util.Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(new String(body, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Serves the editor SPA from classpath resources under {@code webui/}
     * so direct mode works fully offline from inside the plugin jar.
     */
    private void serveStatic(HttpServerExchange exchange) throws Exception {
        String path = exchange.getRelativePath();
        if (path == null || path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        if (!path.matches("[A-Za-z0-9_./-]+") || path.contains("..")) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            exchange.endExchange();
            return;
        }
        try (InputStream in = getClass().getResourceAsStream("/webui" + path)) {
            if (in == null) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.endExchange();
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders()
                    .put(io.undertow.util.Headers.CONTENT_TYPE, contentType(path));
            exchange.setStatusCode(StatusCodes.OK);
            // raw bytes, never a String — the vendored Blockbench build under
            // webui/blockbench/ ships binary assets (fonts, images)
            exchange.getResponseSender().send(java.nio.ByteBuffer.wrap(bytes));
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js") || path.endsWith(".mjs")) {
            return "text/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".json")) {
            return "application/json";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (path.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (path.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (path.endsWith(".woff")) {
            return "font/woff";
        }
        if (path.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (path.endsWith(".wasm")) {
            return "application/wasm";
        }
        return "application/octet-stream";
    }

    private Diagnostic bindFailure(EditorConfig config, int attempted, Exception cause) {
        return new Diagnostic(
                DiagnosticCodes.EDITOR_LISTENER_FAILED,
                dev.materione.api.diagnostics.Severity.ERROR,
                "editor listener failed on " + config.host() + ":" + attempted
                        + " (" + cause.getMessage() + ")",
                null, null, "check editor_port in settings.yml — editing over the "
                        + "watcher/CLI still works without the listener");
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }
}
