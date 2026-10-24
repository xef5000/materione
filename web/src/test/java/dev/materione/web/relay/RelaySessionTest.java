package dev.materione.web.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.web.EditorSessions;
import dev.materione.web.TrustPrompt;
import dev.materione.web.protocol.Envelope;
import dev.materione.web.protocol.FrameCodec;
import dev.materione.web.protocol.HelloMessage;
import dev.materione.web.protocol.HelloReplyMessage;
import dev.materione.web.protocol.KeyFormat;
import dev.materione.web.protocol.SessionBlob;
import dev.materione.web.protocol.SessionCrypto;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.nio.charset.StandardCharsets;
import dev.materione.testing.golden.GoldenFiles;
import com.sun.net.httpserver.HttpServer;
import dev.materione.testing.relay.FakeMailbox;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The whole relay-mode transport, live: a fake-but-contract-accurate mailbox
 * (the shapes pinned by fixtures/protocol/relay-mailbox-v1.json) standing in
 * for the Cloudflare worker, the real {@link RelaySession} pump on it, and
 * the JDK HTTP client playing the browser — blob fetch from the store,
 * fragment-key decryption (v2 blob with the relay block), hello over the
 * mailbox, auto-approved trust, signed-reply verification, key confirmation,
 * then a sealed RPC round trip. The JVM half of the three-sided mailbox
 * fixture pin (worker + plugin + SPA).
 */
class RelaySessionTest {

    private final ConcurrentLinkedQueue<String> published = new ConcurrentLinkedQueue<>();
    private final DiagnosticsBus bus = new Bus();

    private HttpServer server;
    private FakeMailbox mailbox;
    private CompletableFuture<Void> pump;

    @AfterEach
    void stop() {
        if (pump != null) {
            pump.cancel(true);
        }
        if (server != null) {
            server.stop(0);
        }
        published.clear();
    }

    @Test
    void fullHandshakeAndRpcRoundTripOverMailbox() throws Exception {
        mailbox = new FakeMailbox();
        server = mailbox.start();
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        RelayClient client = new RelayClient(java.net.URI.create(base));

        KeyPair identity = KeyFormat.generateEd25519(new SecureRandom());
        EditorSessions sessions = new EditorSessions(
                identity,
                (prefix, browser) -> CompletableFuture.completedFuture(TrustPrompt.Decision.APPROVED),
                null, bus);
        RpcDispatcher dispatcher = new RpcDispatcher();
        dispatcher.register("ping", request -> {
            JsonObject pong = new JsonObject();
            pong.addProperty("pong", true);
            return Wire.Response.success(request, pong);
        });
        RelaySessions relay = new RelaySessions(sessions, dispatcher, bus);

        // --- plugin side: channel + sealed blob + URL + parked pump
        RelaySessions.Ticket ticket = relay.open(client, base, "owner-1", null);
        assertThat(ticket.url()).startsWith(base + "/s/");
        String[] halves = ticket.url().substring(base.length() + 1).split("#", 2);
        String code = halves[0].substring("s/".length());
        String fragment = halves[1];
        pump = CompletableFuture.runAsync(ticket.session()::runLoop);

        // --- browser side: fetch the blob from the store, decrypt (v2 shape)
        String envelopeJson = client.storeGet(code);
        SecretKey wrapping = new javax.crypto.spec.SecretKeySpec(Wire.decode(fragment), "AES");
        Envelope.Opened opened = Envelope.open(wrapping, code, Wire.parseObject(envelopeJson));
        SessionBlob blob = SessionBlob.fromBodyBytes(opened.body());
        assertThat(blob.pluginSigningPubKey())
                .isEqualTo(KeyFormat.ed25519RawPublic(identity.getPublic()));
        assertThat(blob.relay()).isNotNull();
        assertThat(blob.relay().base()).isEqualTo(base);
        assertThat(blob.relay().channelId()).isNotEmpty();
        String editorToken = blob.relay().editorToken();

        // --- hello over the mailbox, signed reply, key confirmation
        KeyPair editorSigning = KeyFormat.generateEd25519(new SecureRandom());
        KeyPair editorEphemeral = KeyFormat.generateP256(new SecureRandom());
        HelloMessage hello = new HelloMessage(
                nonce(), blob.sessionId(), Wire.VERSION,
                KeyFormat.ed25519RawPublic(editorSigning.getPublic()),
                KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) editorEphemeral.getPublic()),
                "JDK-client");
        client.postMessages(blob.relay().channelId(), editorToken,
                List.of(Wire.canonicalJson(hello.toJson())));

        BrowserPoller browser = new BrowserPoller(client, blob.relay().channelId(), editorToken);
        JsonObject payload = Wire.parseObject(browser.next());
        HelloReplyMessage reply = HelloReplyMessage.fromJson(payload.getAsJsonObject("reply"));
        assertThat(SessionCrypto.verify(
                KeyFormat.ed25519Public(blob.pluginSigningPubKey()),
                Wire.canonicalBytes(reply.toJson()),
                Wire.requiredBinary(payload, "sig"))).isTrue();

        byte[] sharedSecret = SessionCrypto.ecdhSharedSecret(
                editorEphemeral.getPrivate(), KeyFormat.p256Public(reply.ecdhPub()));
        byte[] transcriptHash =
                SessionCrypto.transcriptHash(hello.transcriptPart(), reply.transcriptPart());
        assertThat(SessionCrypto.confirmationsMatch(
                reply.confirmation(), SessionCrypto.keyConfirmation(sharedSecret, transcriptHash)))
                .isTrue();
        SecretKey sessionKey = SessionCrypto.sessionKey(sharedSecret, transcriptHash);

        // --- sealed RPC round trip through the mailbox
        FrameCodec browserSender = FrameCodec.sender();
        JsonObject ping = new JsonObject();
        ping.addProperty("op", "ping");
        ping.addProperty("id", "p1");
        ping.add("payload", new JsonObject());
        client.postMessages(blob.relay().channelId(), editorToken, List.of(Wire.canonicalJson(
                browserSender.seal(sessionKey, editorSigning.getPrivate(), Wire.canonicalBytes(ping)))));

        String responseFrame = browser.next();
        byte[] plaintext = FrameCodec.receiver().open(
                sessionKey, KeyFormat.ed25519Public(blob.pluginSigningPubKey()),
                Wire.parseObject(responseFrame));
        Wire.Response response =
                Wire.Response.fromJson(Wire.parseObject(new String(plaintext, StandardCharsets.UTF_8)));
        assertThat(response.ok()).isTrue();
        assertThat(response.data().toString()).contains("pong");

        // --- close is observed by the pump
        client.closeChannel(blob.relay().channelId(), editorToken);
        long deadline = System.currentTimeMillis() + 10_000;
        while (!ticket.session().stopped() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(ticket.session().stopped()).isTrue();
    }

    @Test
    void goldenFixtureTokenHashMatches() throws Exception {
        String fixtureJson = java.nio.file.Files.readString(
                GoldenFiles.fixturesRoot().resolve("protocol").resolve("relay-mailbox-v1.json"));
        JsonObject fixture = Wire.parseObject(fixtureJson)
                .getAsJsonObject("tokenHashing")
                .getAsJsonObject("vector");
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        String hex = HexFormat.of().formatHex(
                sha256.digest(Wire.requiredString(fixture, "input").getBytes(StandardCharsets.UTF_8)));
        assertThat(hex).isEqualTo(Wire.requiredString(fixture, "sha256Hex"));
    }

    // ------------------------------------------------------------------

    /** Browser-side sequential reader: each {@link #next()} yields the next
     * message in the editor's direction, keeping the cursor internally. */
    private static final class BrowserPoller {
        private final RelayClient client;
        private final String channelId;
        private final String token;
        private long cursor = 0;

        BrowserPoller(RelayClient client, String channelId, String token) {
            this.client = client;
            this.channelId = channelId;
            this.token = token;
        }

        String next() throws Exception {
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                RelayClient.Poll poll = client.poll(channelId, token, cursor, 2);
                if (!poll.messages().isEmpty()) {
                    String text = poll.messages().get(poll.messages().size() - 1).messageText();
                    cursor = poll.messages().get(poll.messages().size() - 1).id();
                    return text;
                }
            }
            throw new AssertionError("no mailbox message arrived in time");
        }
    }

    private static byte[] nonce() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private final class Bus implements DiagnosticsBus {
        @Override
        public void publish(Diagnostic diagnostic) {
            published.offer(diagnostic.code().code());
        }

        @Override
        public AutoCloseable subscribe(java.util.function.Consumer<Diagnostic> consumer) {
            return () -> {};
        }

        @Override
        public int count(dev.materione.api.diagnostics.Severity severity) {
            return 0;
        }

        @Override
        public List<Diagnostic> recent(int limit) {
            return List.of();
        }
    }

    // The mailbox stand-in lives in testing testFixtures (FakeMailbox):
    // same endpoints and shapes as the deployed worker, virtual-thread executor.
}
