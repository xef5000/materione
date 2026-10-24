package dev.materione.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.web.protocol.Envelope;
import dev.materione.web.protocol.FrameCodec;
import dev.materione.web.protocol.HelloMessage;
import dev.materione.web.protocol.HelloReplyMessage;
import dev.materione.web.protocol.KeyFormat;
import dev.materione.web.protocol.SessionBlob;
import dev.materione.web.protocol.SessionCrypto;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The whole direct-mode transport, live: real listener on a real loopback
 * port, with the JDK HTTP/WebSocket client standing in for the browser —
 * blob fetch, fragment-key decryption, hello, in-game trust (auto-approve),
 * signed reply verification, key confirmation, then a sealed RPC round trip.
 * This is the JVM half of what {@code fixtures/protocol} pins byte-for-byte.
 */
class EditorListenerTest {

    /** Test ports sit high and random; the listener may walk up 8 more. */
    private static final int PORT_BASE = 25000;
    private static final int PORT_SPREAD = 20000;

    private final ConcurrentLinkedQueue<String> published = new ConcurrentLinkedQueue<>();
    private final DiagnosticsBus bus = new Bus();

    private EditorListener listener;

    @AfterEach
    void stopListener() {
        if (listener != null) {
            listener.stop();
        }
        published.clear();
    }

    @Test
    void fullHandshakeAndRpcRoundTripOverRealSocket() throws Exception {
        int port = PORT_BASE + new SecureRandom().nextInt(PORT_SPREAD);
        KeyPair identity = KeyFormat.generateEd25519(new SecureRandom());
        TrustPrompt autoApprove = (prefix, browser) ->
                CompletableFuture.completedFuture(TrustPrompt.Decision.APPROVED);
        EditorSessions sessions = new EditorSessions(identity, autoApprove, null, bus);

        RpcDispatcher dispatcher = new RpcDispatcher();
        dispatcher.register("ping", request -> {
            JsonObject pong = new JsonObject();
            pong.addProperty("pong", true);
            return Wire.Response.success(request, pong);
        });

        listener = new EditorListener(sessions, new EditorSockets(sessions, dispatcher, bus), dispatcher, bus);
        assertThat(listener.start(new EditorConfig("127.0.0.1", port))).isTrue();

        // --- capability URL → blob fetch → decrypt (the browser's first steps)
        String url = listener.openSessionUrl(null);
        URI uri = URI.create(url);
        String code = uri.getQuery().substring("s=".length());
        String fragment = uri.getFragment();

        HttpClient http = HttpClient.newHttpClient();
        var blobResponse = http.send(
                java.net.http.HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + listener.boundPort() + "/s/" + code))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(blobResponse.statusCode()).isEqualTo(200);

        SecretKey wrapping = new javax.crypto.spec.SecretKeySpec(Wire.decode(fragment), "AES");
        Envelope.Opened opened =
                Envelope.open(wrapping, code, Wire.parseObject(blobResponse.body()));
        SessionBlob sessionBlob = SessionBlob.fromBodyBytes(opened.body());
        assertThat(sessionBlob.pluginSigningPubKey())
                .isEqualTo(KeyFormat.ed25519RawPublic(identity.getPublic()));

        // --- WS connect, hello, signed reply, key confirmation
        KeyPair editorSigning = KeyFormat.generateEd25519(new SecureRandom());
        KeyPair editorEphemeral = KeyFormat.generateP256(new SecureRandom());
        HelloMessage hello = new HelloMessage(
                nonce(), code, Wire.VERSION,
                KeyFormat.ed25519RawPublic(editorSigning.getPublic()),
                KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) editorEphemeral.getPublic()),
                "JDK-client");

        WsClient wsClient = new WsClient();
        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + listener.boundPort() + "/editor/ws"), wsClient)
                .get(10, TimeUnit.SECONDS);
        ws.sendText(Wire.canonicalJson(hello.toJson()), true).get(10, TimeUnit.SECONDS);

        String signedReply = wsClient.firstMessage.get(15, TimeUnit.SECONDS);
        JsonObject payload = Wire.parseObject(signedReply);
        HelloReplyMessage reply = HelloReplyMessage.fromJson(payload.getAsJsonObject("reply"));
        boolean signatureOk = SessionCrypto.verify(
                KeyFormat.ed25519Public(sessionBlob.pluginSigningPubKey()),
                Wire.canonicalBytes(reply.toJson()),
                Wire.requiredBinary(payload, "sig"));
        assertThat(signatureOk).isTrue();

        byte[] sharedSecret = SessionCrypto.ecdhSharedSecret(
                editorEphemeral.getPrivate(), KeyFormat.p256Public(reply.ecdhPub()));
        byte[] transcriptHash =
                SessionCrypto.transcriptHash(hello.transcriptPart(), reply.transcriptPart());
        assertThat(SessionCrypto.confirmationsMatch(
                reply.confirmation(), SessionCrypto.keyConfirmation(sharedSecret, transcriptHash)))
                .isTrue();
        SecretKey sessionKey = SessionCrypto.sessionKey(sharedSecret, transcriptHash);
        assertThat(listener.activeSessions()).isEqualTo(1);

        // --- sealed RPC round trip: browser sends ping, expects ok:true
        FrameCodec browserSender = FrameCodec.sender();
        JsonObject ping = new JsonObject();
        ping.addProperty("op", "ping");
        ping.addProperty("id", "p1");
        ping.add("payload", new JsonObject());
        ws.sendText(Wire.canonicalJson(browserSender.seal(
                        sessionKey, editorSigning.getPrivate(), Wire.canonicalBytes(ping))),
                true)
                .get(10, TimeUnit.SECONDS);

        String responseFrame = wsClient.awaitFrame(15, TimeUnit.SECONDS);
        byte[] plaintext = FrameCodec.receiver()
                .open(sessionKey, KeyFormat.ed25519Public(sessionBlob.pluginSigningPubKey()),
                        Wire.parseObject(responseFrame));
        Wire.Response response = Wire.Response.fromJson(Wire.parseObject(new String(plaintext, StandardCharsets.UTF_8)));
        assertThat(response.ok()).isTrue();
        assertThat(response.data().toString()).contains("pong");

        ws.abort();
    }

    @Test
    void unknownSessionHelloIsRejectedCleanly() throws Exception {
        int port = PORT_BASE + new SecureRandom().nextInt(PORT_SPREAD);
        KeyPair identity = KeyFormat.generateEd25519(new SecureRandom());
        EditorSessions sessions = new EditorSessions(
                identity, (prefix, browser) ->
                        CompletableFuture.completedFuture(TrustPrompt.Decision.APPROVED),
                null, bus);
        listener = new EditorListener(
                sessions, new EditorSockets(sessions, new RpcDispatcher(), bus), new RpcDispatcher(), bus);
        assertThat(listener.start(new EditorConfig("127.0.0.1", port))).isTrue();

        HttpClient http = HttpClient.newHttpClient();
        WsClient client = new WsClient();
        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + listener.boundPort() + "/editor/ws"), client)
                .get(10, TimeUnit.SECONDS);
        HelloMessage stranger = new HelloMessage(
                nonce(), "unknown-session-id-00000000000", Wire.VERSION,
                KeyFormat.ed25519RawPublic(KeyFormat.generateEd25519(new SecureRandom()).getPublic()),
                KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) KeyFormat
                        .generateP256(new SecureRandom()).getPublic()),
                "stranger");
        ws.sendText(Wire.canonicalJson(stranger.toJson()), true).get(5, TimeUnit.SECONDS);

        long deadline = System.currentTimeMillis() + 5000;
        while (!published.contains("MTR-0033") && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(published).contains("MTR-0033");
        ws.abort();
    }

    // ------------------------------------------------------------------

    /** Thin bus wrapper collecting codes; the real service works too but this keeps tests hermetic. */
    private final class Bus implements DiagnosticsBus {
        @Override
        public void publish(Diagnostic diagnostic) {
            published.offer(diagnostic.code().code());
        }

        @Override
        public AutoCloseable subscribe(java.util.function.Consumer<Diagnostic> consumer) {
            // tests read the published queue directly
            return () -> {};
        }

        @Override
        public int count(dev.materione.api.diagnostics.Severity severity) {
            return 0;
        }

        @Override
        public java.util.List<Diagnostic> recent(int limit) {
            return java.util.List.of();
        }
    }

    private static byte[] nonce() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /** Collects server messages; first is the signed hello-reply, rest are frames. */
    private static final class WsClient implements WebSocket.Listener {

        private final CompletableFuture<String> firstMessage = new CompletableFuture<>();
        private final java.util.concurrent.BlockingQueue<String> frames =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String complete = partial.toString();
                partial = new StringBuilder();
                if (!firstMessage.isDone()) {
                    firstMessage.complete(complete);
                } else {
                    frames.offer(complete);
                }
            }
            webSocket.request(1);
            return null;
        }

        String awaitFrame(long timeout, TimeUnit unit) throws Exception {
            String frame = frames.poll(timeout, unit);
            if (frame == null) {
                throw new AssertionError("no frame arrived in time");
            }
            return frame;
        }
    }
}
