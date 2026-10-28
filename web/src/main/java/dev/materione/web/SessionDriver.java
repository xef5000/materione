package dev.materione.web;

import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.web.protocol.HelloMessage;
import dev.materione.web.protocol.ProbeMessage;
import dev.materione.web.protocol.ProtocolException;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;

/**
 * Transport-neutral inbound driver for one editor connection (web.md §4):
 * the first channel message is the {@code hello} (which awaits in-game trust
 * and yields the signed reply), everything after is a sealed frame routed
 * through the {@link RpcDispatcher}. Extracted from the M3 WebSocket adapter
 * so relay mode (long-poll mailbox) drives the exact same logic.
 *
 * <p>The transport implements {@link Out}: {@code send} writes to the wire,
 * {@code violation} tears the connection down. Sequencing guarantee: the
 * hello reply is handed to {@code send} strictly before
 * {@link EditorSession#announceActive()} lets pushes race onto the channel.
 */
public final class SessionDriver {

    /** Transport callbacks. */
    public interface Out {
        void send(String text);

        void violation(String detail);

        /**
         * True only for direct sockets: the pre-hello reachability probe
         * (web.md §4.4) is a signing exchange, and the untrusted relay must
         * never be offered one.
         */
        default boolean acceptsProbes() {
            return false;
        }

        /** Quietly ends a connection that asked for something this server cannot serve (no diagnostic). */
        default void decline() {}
    }

    private final EditorSessions sessions;
    private final RpcDispatcher dispatcher;
    private final DiagnosticsBus diagnostics;
    private final Out out;

    private EditorSession session;
    private boolean helloSeen;
    private boolean probeSeen;

    public SessionDriver(
            EditorSessions sessions, RpcDispatcher dispatcher, DiagnosticsBus diagnostics, Out out) {
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        this.diagnostics = diagnostics;
        this.out = out;
    }

    /** Feeds one inbound channel message (hello first, sealed frames after). */
    public void handle(String messageText) {
        if (!helloSeen) {
            if (out.acceptsProbes() && ProbeMessage.looksLikeProbe(messageText)) {
                handleProbe(messageText);
                return;
            }
            helloSeen = true;
            beginHello(messageText);
            return;
        }
        handleFrame(messageText);
    }

    /** True once a hello claimed a session (the connection is bound). */
    public boolean bound() {
        return session != null;
    }

    public EditorSession session() {
        return session;
    }

    /** Releases the bound session (transport teardown). */
    public void unbind() {
        if (session != null) {
            session.close();
            sessions.reapClosed();
            session = null;
        }
    }

    // ------------------------------------------------------------------

    /**
     * The pre-hello reachability probe: at most one per connection, signed
     * only for a session this server really holds and nobody has claimed.
     * Anything else is declined without a diagnostic — a stray probe (another
     * Materione instance on the same address, a stale tab) is expected noise,
     * not a violation, and the browser simply stays on the relay.
     */
    private void handleProbe(String text) {
        if (probeSeen) {
            out.violation("repeated probe");
            return;
        }
        probeSeen = true;
        try {
            ProbeMessage.Request request = ProbeMessage.parse(text);
            EditorSession target = sessions.find(request.sessionId());
            var signature = target == null
                    ? java.util.Optional.<byte[]>empty()
                    : target.answerProbe(request.challenge());
            if (signature.isEmpty()) {
                out.decline();
                return;
            }
            out.send(Wire.canonicalJson(ProbeMessage.reply(request.sessionId(), signature.get())));
        } catch (ProtocolException e) {
            out.decline();
        }
    }

    private void beginHello(String text) {
        try {
            HelloMessage hello = HelloMessage.fromJson(Wire.parseObject(text));
            EditorSession claimed = sessions.find(hello.sessionId());
            if (claimed == null) {
                throw new ProtocolException(ProtocolException.Kind.HANDSHAKE, "unknown session");
            }
            if (claimed.isActive()) {
                throw new ProtocolException(ProtocolException.Kind.HANDSHAKE,
                        "session already has a connected editor — create another session for "
                                + "a second browser");
            }
            this.session = claimed;
            // The trust prompt answers out-of-band (in game); nothing here blocks.
            claimed.beginHello(hello, out::send).whenComplete((reply, error) -> {
                if (error != null) {
                    out.violation(rootMessage(error));
                } else {
                    out.send(reply);
                    // Only NOW may pushes (diagnostics, reloads) start racing
                    // frames to this channel — the reply is on the wire first.
                    claimed.announceActive();
                }
            });
        } catch (ProtocolException e) {
            out.violation(e.getMessage());
        } catch (Exception e) {
            out.violation("malformed hello: " + e.getMessage());
        }
    }

    private void handleFrame(String text) {
        try {
            if (session == null || !session.isActive()) {
                throw new ProtocolException(ProtocolException.Kind.HANDSHAKE, "frame before handshake");
            }
            byte[] body = session.openFrame(Wire.parseObject(text));
            Wire.Request request = Wire.Request.fromJson(
                    Wire.parseObject(new String(body, java.nio.charset.StandardCharsets.UTF_8)));
            Wire.Response response = dispatcher.dispatch(request, session.sessionId());
            session.sendFrame(Wire.canonicalBytes(response.toJson()));
        } catch (ProtocolException e) {
            out.violation(e.getMessage());
        } catch (Exception e) {
            out.violation("frame handling failed: " + e.getMessage());
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
