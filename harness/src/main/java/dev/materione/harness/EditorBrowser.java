package dev.materione.harness;

import com.google.gson.JsonObject;
import dev.materione.web.protocol.Envelope;
import dev.materione.web.protocol.FrameCodec;
import dev.materione.web.protocol.HelloMessage;
import dev.materione.web.protocol.HelloReplyMessage;
import dev.materione.web.protocol.KeyFormat;
import dev.materione.web.protocol.SessionBlob;
import dev.materione.web.protocol.SessionCrypto;
import dev.materione.web.protocol.Wire;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;

/**
 * A browser, played by the harness, against the live editor listener: the console opens a local
 * session, a JDK HTTP/WebSocket client fetches the capability blob, decrypts it with the fragment key,
 * sends a hello, the console approves it by nonce, and the signed reply and key confirmation are
 * verified. The result is a {@link BrowserClient} speaking sealed RPC.
 *
 * <p>Shared by every scenario that drives the editor ({@code EditorHandshakeRunner},
 * {@code GuiRuntimeRunner}), so the handshake exists in one place. This class is a protocol consumer,
 * exactly like the SPA: drift in {@code dev.materione.web.protocol} breaks here first.
 */
final class EditorBrowser {

    private EditorBrowser() {}

    /**
     * Opens a local session through the console and completes the handshake as a browser would.
     *
     * @return the connected browser, or null when a step failed; {@code problems} says which step
     */
    static BrowserClient connect(ConsoleDriver console, LogWatcher log, HarnessOptions options,
            List<String> problems) throws Exception {
        // 1. open a session via console, scrape the capability URL
        int cursor = log.mark();
        // The direct-mode gate: pin the local surface explicitly — bare 'materione
        // editor' now defaults to the cloud relay, which CI runners must not dial.
        console.send("materione editor local");
        String urlLine = log.awaitLine("MATERIONE-EDITOR http://", options.commandTimeout(), cursor);
        if (urlLine == null) {
            problems.add("MATERIONE-EDITOR url missing after 'materione editor'");
            return null;
        }
        String url = urlLine.substring(urlLine.indexOf("http://")).trim();
        URI uri = URI.create(url);
        String code = uri.getQuery().substring("s=".length());
        String fragmentKey = uri.getFragment();
        String wsUrl = "ws://" + uri.getHost() + ":" + uri.getPort() + "/editor/ws";
        String blobUrl = "http://" + uri.getHost() + ":" + uri.getPort() + "/s/" + code;

        // 2. browser step one: the SPA itself is served from the jar...
        HttpClient http = HttpClient.newHttpClient();
        var spaResponse = http.send(
                java.net.http.HttpRequest.newBuilder(
                                URI.create("http://" + uri.getHost() + ":" + uri.getPort() + "/"))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (!spaResponse.body().contains("Materione")) {
            problems.add("editor SPA index not served at /");
        }
        // ...and the capability fetch decrypts with the fragment key.
        var blobResponse = http.send(
                java.net.http.HttpRequest.newBuilder(URI.create(blobUrl)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (blobResponse.statusCode() != 200) {
            problems.add("session blob fetch returned " + blobResponse.statusCode());
            return null;
        }
        SecretKey wrapping = new javax.crypto.spec.SecretKeySpec(Wire.decode(fragmentKey), "AES");
        SessionBlob blob = SessionBlob.fromBodyBytes(Envelope.open(
                wrapping, code, Wire.parseObject(blobResponse.body())).body());

        // 3. hello with a nonce WE know (so the console can approve it)
        SecureRandom random = new SecureRandom();
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String noncePrefix = HexFormat.of().formatHex(nonce, 0, 4);
        KeyPair editorSigning = KeyFormat.generateEd25519(random);
        KeyPair editorEphemeral = KeyFormat.generateP256(random);

        WsClient wsClient = new WsClient();
        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), wsClient)
                .get(10, TimeUnit.SECONDS);
        HelloMessage hello = new HelloMessage(
                nonce, code, Wire.VERSION,
                KeyFormat.ed25519RawPublic(editorSigning.getPublic()),
                KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) editorEphemeral.getPublic()),
                "harness-client");
        int promptCursor = log.mark();
        ws.sendText(Wire.canonicalJson(hello.toJson()), true).get(10, TimeUnit.SECONDS);

        // 4. wait for the trust prompt to surface, THEN answer it from
        // the console — the approve must never race the registration
        if (log.awaitLine("wants to connect as editor", options.commandTimeout(), promptCursor) == null) {
            problems.add("trust prompt never surfaced for nonce " + noncePrefix);
        }
        int approvedCursor = log.mark();
        console.send("materione editor approve " + noncePrefix);
        if (log.awaitLine("MTR-0032", options.commandTimeout(), approvedCursor) == null) {
            problems.add("approval did not activate the session (MTR-0032 missing)");
        }

        // 5. signed reply + key confirmation
        JsonObject payload = Wire.parseObject(wsClient.first.get(20, TimeUnit.SECONDS));
        HelloReplyMessage reply = HelloReplyMessage.fromJson(payload.getAsJsonObject("reply"));
        boolean signed = SessionCrypto.verify(
                KeyFormat.ed25519Public(blob.pluginSigningPubKey()),
                Wire.canonicalBytes(reply.toJson()),
                Wire.requiredBinary(payload, "sig"));
        if (!signed) {
            problems.add("hello-reply signature invalid");
        }
        byte[] sharedSecret = SessionCrypto.ecdhSharedSecret(
                editorEphemeral.getPrivate(), KeyFormat.p256Public(reply.ecdhPub()));
        byte[] transcriptHash =
                SessionCrypto.transcriptHash(hello.transcriptPart(), reply.transcriptPart());
        if (!SessionCrypto.confirmationsMatch(
                reply.confirmation(), SessionCrypto.keyConfirmation(sharedSecret, transcriptHash))) {
            problems.add("key confirmation mismatch");
        }
        SecretKey sessionKey = SessionCrypto.sessionKey(sharedSecret, transcriptHash);
        return new BrowserClient(ws, wsClient, sessionKey, editorSigning.getPrivate(),
                KeyFormat.ed25519Public(blob.pluginSigningPubKey()));
    }

    /** Alternating key/value pairs; numeric strings become ints. */
    static JsonObject object(String... pairs) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            String value = pairs[i + 1];
            if (value.matches("-?\\d+")) {
                object.addProperty(pairs[i], Integer.parseInt(value));
            } else {
                object.addProperty(pairs[i], value);
            }
        }
        return object;
    }

    /** Collects server→browser messages: first the signed reply, then frames.
     * Frames arriving before the browser client attaches buffer instead of
     * dropping — a dropped frame would desync the strict receive sequence. */
    private static final class WsClient implements WebSocket.Listener {
        private final CompletableFuture<String> first = new CompletableFuture<>();
        private java.util.function.Consumer<String> frameConsumer;
        private final java.util.Deque<String> buffered = new java.util.concurrent.ConcurrentLinkedDeque<>();
        private StringBuilder partial = new StringBuilder();

        /** Routes every post-handshake frame text to the browser client. */
        void onFrame(java.util.function.Consumer<String> consumer) {
            this.frameConsumer = consumer;
            while (!buffered.isEmpty()) {
                consumer.accept(buffered.poll());
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String complete = partial.toString();
                partial = new StringBuilder();
                if (!first.isDone()) {
                    first.complete(complete);
                } else if (frameConsumer != null) {
                    frameConsumer.accept(complete);
                } else {
                    buffered.add(complete);
                }
            }
            webSocket.request(1);
            return null;
        }
    }

    /**
     * Sealed RPC from the browser side. Frames decrypt here exactly once:
     * responses resolve their caller, server pushes land on {@code pushes}
     * (the live-preview channel the budget assertion reads).
     */
    static final class BrowserClient {
        private final WebSocket ws;
        private final SecretKey sessionKey;
        private final java.security.PrivateKey editorSigning;
        private final java.security.PublicKey pluginSigning;
        private final FrameCodec sender = FrameCodec.sender();
        private final FrameCodec receiver = FrameCodec.receiver();
        private final java.util.Map<String, java.util.function.Consumer<Wire.Response>> waiters =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.BlockingQueue<Wire.Request> pushes =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private long nextId;

        private BrowserClient(WebSocket ws, WsClient sink, SecretKey sessionKey,
                java.security.PrivateKey editorSigning, java.security.PublicKey pluginSigning) {
            this.ws = ws;
            this.sessionKey = sessionKey;
            this.editorSigning = editorSigning;
            this.pluginSigning = pluginSigning;
            sink.onFrame(this::consume);
        }

        private void consume(String frameText) {
            try {
                byte[] body = receiver.open(sessionKey, pluginSigning, Wire.parseObject(frameText));
                JsonObject message = Wire.parseObject(new String(body, StandardCharsets.UTF_8));
                if (message.has("ok")) {
                    var waiter = waiters.remove(message.get("id").getAsString());
                    if (waiter != null) {
                        waiter.accept(Wire.Response.fromJson(message));
                    }
                } else if (message.has("op")) {
                    pushes.offer(Wire.Request.fromJson(message));
                }
            } catch (Exception e) {
                throw new RuntimeException("frame handling failed", e);
            }
        }

        Wire.Response call(String op, JsonObject payload) throws Exception {
            String id = "c" + (++nextId);
            CompletableFuture<Wire.Response> future = new CompletableFuture<>();
            waiters.put(id, future::complete);
            JsonObject request = new JsonObject();
            request.addProperty("op", op);
            request.addProperty("id", id);
            request.add("payload", payload);
            JsonObject frame = sender.seal(sessionKey, editorSigning, Wire.canonicalBytes(request));
            ws.sendText(Wire.canonicalJson(frame), true).get(10, TimeUnit.SECONDS);
            return future.get(20, TimeUnit.SECONDS);
        }

        /** Waits for one pushed event of the given op; null on timeout. */
        Wire.Request awaitPush(String op, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            while (System.currentTimeMillis() < deadline) {
                Wire.Request push = pushes.poll(deadline - System.currentTimeMillis(),
                        TimeUnit.MILLISECONDS);
                if (push == null) {
                    return null;
                }
                if (op.equals(push.op())) {
                    return push;
                }
            }
            return null;
        }

        /**
         * Waits for a pushed event of the given op whose payload contains
         * {@code payloadNeedle} — earlier pushes of the same op (e.g. the
         * deleted notification an edit.move legitimately emits) are skipped.
         */
        Wire.Request awaitPush(String op, String payloadNeedle, long timeout, TimeUnit unit)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            while (System.currentTimeMillis() < deadline) {
                Wire.Request push = awaitPush(op, Math.max(1, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
                if (push == null) {
                    return null;
                }
                if (push.payload().toString().contains(payloadNeedle)) {
                    return push;
                }
            }
            return null;
        }

        /** Tears the socket down; the scenario's last step. */
        void close() {
            ws.abort();
        }
    }
}
