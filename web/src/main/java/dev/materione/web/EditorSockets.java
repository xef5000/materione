package dev.materione.web;

import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.web.protocol.HelloMessage;
import dev.materione.web.protocol.ProtocolException;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;

/**
 * The per-connection WS adapter: drives the session state machine (hello →
 * trust → frames) and routes decrypted requests through the
 * {@link RpcDispatcher}. One socket belongs to at most one session; a
 * protocol violation closes the socket and drops only that session (D6).
 *
 * <p>The hello path never blocks an XNIO worker thread: {@code beginHello}
 * returns a future, and the reply is written from the completion callback.
 */
public final class EditorSockets {

    private final EditorSessions sessions;
    private final RpcDispatcher dispatcher;
    private final DiagnosticsBus diagnostics;

    public EditorSockets(EditorSessions sessions, RpcDispatcher dispatcher, DiagnosticsBus diagnostics) {
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        this.diagnostics = diagnostics;
    }

    /** Wires a freshly upgraded channel to its receive listener. */
    void attach(WebSocketChannel channel) {
        Connection connection = new Connection(channel);
        channel.getReceiveSetter().set(connection);
        channel.getCloseSetter().set(ch -> connection.closed());
        channel.resumeReceives();
    }

    private final class Connection extends AbstractReceiveListener implements EditorSession.FrameSink {

        private final WebSocketChannel channel;
        private EditorSession session;
        private boolean helloSeen;

        Connection(WebSocketChannel channel) {
            this.channel = channel;
        }

        @Override
        protected void onFullTextMessage(WebSocketChannel active, BufferedTextMessage message) {
            String text = message.getData();
            if (!helloSeen) {
                helloSeen = true;
                beginHello(text);
                return;
            }
            handleFrame(text);
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
                claimed.beginHello(hello, this).whenComplete((reply, error) -> {
                    if (error != null) {
                        violation(rootMessage(error));
                    } else {
                        send(reply);
                        // Only NOW may pushes (diagnostics, reloads) start
                        // racing frames to this socket — see announceActive().
                        claimed.announceActive();
                    }
                });
            } catch (ProtocolException e) {
                violation(e.getMessage());
            } catch (Exception e) {
                violation("malformed hello: " + e.getMessage());
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
                violation(e.getMessage());
            } catch (Exception e) {
                violation("frame handling failed: " + e.getMessage());
            }
        }

        @Override
        public void accept(String frameText) {
            send(frameText);
        }

        private void send(String text) {
            if (channel.isOpen()) {
                WebSockets.sendText(text, channel, null);
            }
        }

        private void closed() {
            if (session != null) {
                session.close();
                sessions.reapClosed();
                session = null;
            }
        }

        private void violation(String detail) {
            diagnostics.publish(dev.materione.api.diagnostics.Diagnostic.warn(
                    DiagnosticCodes.EDITOR_PROTOCOL_VIOLATION,
                    detail + " — dropping editor session"));
            closed();
            if (channel.isOpen()) {
                WebSockets.sendClose(4400, "protocol violation", channel, null);
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
}
