package dev.materione.web;

import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.web.rpc.RpcDispatcher;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;

/**
 * The per-connection WS adapter (direct mode): wires an upgraded socket to a
 * {@link SessionDriver} — hello → trust → frames — and closes the socket on
 * violations. One socket belongs to at most one session; a protocol
 * violation drops only that session (D6). The hello path never blocks an
 * XNIO worker thread: the driver's trust future completes out-of-band.
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

    private final class Connection extends AbstractReceiveListener implements SessionDriver.Out {

        private final WebSocketChannel channel;
        private final SessionDriver driver;

        Connection(WebSocketChannel channel) {
            this.channel = channel;
            this.driver = new SessionDriver(sessions, dispatcher, diagnostics, this);
        }

        @Override
        protected void onFullTextMessage(WebSocketChannel active, BufferedTextMessage message) {
            driver.handle(message.getData());
        }

        @Override
        public void send(String text) {
            if (channel.isOpen()) {
                WebSockets.sendText(text, channel, null);
            }
        }

        private void closed() {
            driver.unbind();
        }

        @Override
        public void violation(String detail) {
            diagnostics.publish(dev.materione.api.diagnostics.Diagnostic.warn(
                    DiagnosticCodes.EDITOR_PROTOCOL_VIOLATION,
                    detail + " — dropping editor session"));
            driver.unbind();
            if (channel.isOpen()) {
                WebSockets.sendClose(4400, "protocol violation", channel, null);
            }
        }
    }
}
