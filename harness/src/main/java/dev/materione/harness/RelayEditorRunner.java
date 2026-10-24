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
import dev.materione.web.relay.RelayClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;

/**
 * The M5 relay gate (roadmap M5; web.md §3/§4.1), live on Paper: the plugin's
 * DEFAULT editor surface — the cloud relay — exercised end to end with the
 * contract-accurate fake mailbox standing in for the Cloudflare worker. The
 * full cloud path runs here: channel create, sealed blob in the payload
 * store, capability URL, mailbox hello, in-game trust approval, signed reply,
 * key confirmation, then sealed RPC + one save→apply round trip whose
 * push.reload proves the parked pump wakes for server-pushed frames.
 *
 * <p>The caller supplies the fake mailbox's base URL via both the injected
 * settings ({@code relay_base_url}) and the runner argument, so CI never
 * dials the real internet and the plugin never opens an inbound port.
 */
public final class RelayEditorRunner {

    public HarnessResult run(HarnessOptions options, String relayBase) {
        ServerWorkspace workspace;
        try {
            workspace = ServerWorkspace.create(options, "editor-relay", Set.of("sample", "corpus"));
        } catch (Exception e) {
            return failure(List.of("could not create workspace: " + e), -1, null);
        }

        LogWatcher log = new LogWatcher(workspace.logFile);
        Process process;
        try {
            process = new ProcessBuilder(
                    options.javaExecutable().toString(),
                    "-Xmx2G",
                    "-Dfile.encoding=UTF-8",
                    "-DIReallyKnowWhatIAmDoingISwear",
                    "-jar",
                    options.paperJar().toString(),
                    "nogui")
                    .directory(workspace.root.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(workspace.stdoutFile.toFile())
                    .start();
        } catch (Exception e) {
            return failure(List.of("could not start server: " + e), -1, workspace);
        }

        List<String> problems = new ArrayList<>();
        try (ConsoleDriver console = new ConsoleDriver(process.getOutputStream())) {
            if (await(log, "Done (", options.bootTimeout()) == null) {
                problems.add("boot deadline exceeded");
                return finish(process, log, options, problems, workspace);
            }
            if (log.readAll() == null || !log.readAll().contains("MATERIONE-BOOT-OK")) {
                problems.add("MATERIONE-BOOT-OK missing");
            }

            // 1. the DEFAULT surface — plain 'materione editor' is the cloud
            // relay now; the gate fails if anything but the cloud path answers
            console.send("materione editor");
            String urlLine = log.awaitLine(
                    "MATERIONE-EDITOR " + relayBase + "/?s=", options.commandTimeout(), log.mark());
            if (urlLine == null) {
                problems.add("MATERIONE-EDITOR cloud url missing after 'materione editor'"
                        + " (the relay path fell back — see MTR-0050 in the log)");
                return finish(process, log, options, problems, workspace);
            }
            String url = urlLine.substring(urlLine.indexOf("http://")).trim();
            URI uri = URI.create(url);
            // Capability URLs are origin + ?s={code}#{key} — the SPA boots
            // from / and fetches the blob from /s/{code} on the same origin.
            String query = uri.getQuery();
            if (query == null || !query.startsWith("s=") || uri.getFragment() == null) {
                problems.add("MATERIONE-EDITOR url is not a relay capability shape: " + uri);
                return finish(process, log, options, problems, workspace);
            }
            String code = query.substring("s=".length());
            String fragmentKey = uri.getFragment();

            // 2. browser step one: fetch the sealed blob from the payload
            // store and open it with the fragment key (envelope binds to the
            // plugin-minted store code — the relay-mode binding)
            RelayClient client = new RelayClient(URI.create(relayBase));
            String envelopeJson = client.storeGet(code);
            if (envelopeJson == null) {
                problems.add("session blob missing from the payload store");
                return finish(process, log, options, problems, workspace);
            }
            SecretKey wrapping = new javax.crypto.spec.SecretKeySpec(Wire.decode(fragmentKey), "AES");
            SessionBlob blob = SessionBlob.fromBodyBytes(Envelope.open(
                    wrapping, code, Wire.parseObject(envelopeJson)).body());
            if (blob.relay() == null
                    || blob.relay().channelId().isEmpty()
                    || blob.relay().editorToken().isEmpty()) {
                problems.add("session blob carries no relay capability (v2 shape)");
                return finish(process, log, options, problems, workspace);
            }
            String channelId = blob.relay().channelId();
            String editorToken = blob.relay().editorToken();

            // 3. hello over the mailbox — plaintext, schema-blind transport
            SecureRandom random = new SecureRandom();
            byte[] nonce = new byte[16];
            random.nextBytes(nonce);
            String noncePrefix = HexFormat.of().formatHex(nonce, 0, 4);
            KeyPair editorSigning = KeyFormat.generateEd25519(random);
            KeyPair editorEphemeral = KeyFormat.generateP256(random);
            HelloMessage hello = new HelloMessage(
                    nonce, blob.sessionId(), Wire.VERSION,
                    KeyFormat.ed25519RawPublic(editorSigning.getPublic()),
                    KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) editorEphemeral.getPublic()),
                    "harness-client");
            client.postMessages(channelId, editorToken, List.of(Wire.canonicalJson(hello.toJson())));

            // 4. trust prompt → console approval — identical UX to direct mode
            int promptCursor = log.mark();
            if (log.awaitLine("wants to connect as editor", options.commandTimeout(), promptCursor) == null) {
                problems.add("trust prompt never surfaced for nonce " + noncePrefix);
            }
            console.send("materione editor approve " + noncePrefix);
            int approvedCursor = log.mark();
            if (log.awaitLine("MTR-0032", options.commandTimeout(), approvedCursor) == null) {
                problems.add("approval did not activate the session (MTR-0032 missing)");
            }

            // 5. signed reply + key confirmation. The reader takes messages
            // strictly in order — the reply and the approval's diagnostic push
            // can share one mailbox batch, so "last" would eat the reply.
            MailboxReader reader = new MailboxReader(client, channelId, editorToken);
            JsonObject payload = Wire.parseObject(reader.next());
            HelloReplyMessage reply =
                    HelloReplyMessage.fromJson(payload.getAsJsonObject("reply"));
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

            // 6. sealed RPC + the live-preview loop over the mailbox
            RelayBrowser browser = new RelayBrowser(
                    client, channelId, editorToken, reader, sessionKey,
                    editorSigning.getPrivate(), KeyFormat.ed25519Public(blob.pluginSigningPubKey()));
            Wire.Response pong = browser.call("ping", new JsonObject());
            if (!pong.ok() || !pong.data().toString().contains("pong")) {
                problems.add("ping round trip wrong response: " + pong.data());
            }
            Wire.Response put = browser.call("edit.put", object(
                    "pack", "sample",
                    "path", "items/editor_gem.yml",
                    "rev", "0",
                    "content", "# created by the harness\n"
                            + "editor_gem:\n"
                            + "  material: AMETHYST_SHARD\n"
                            + "  texture: corpus/red\n"
                            + "  display_name: \"Editor Gem\"\n"));
            if (!put.ok()) {
                problems.add("edit.put failed: " + put.error());
            }
            Wire.Request reloadPush = browser.awaitPush("push.reload", 20, TimeUnit.SECONDS);
            if (reloadPush == null) {
                problems.add("no push.reload after save over the relay"
                        + " — the parked pump never woke for the queued push");
            }
            // (no 2 s budget here: that SLA is the direct-mode gate; over the
            // relay the gate pins correctness — real-internet latency varies)
            Wire.Response got = browser.call("browse.get", object(
                    "pack", "sample", "path", "items/editor_gem.yml"));
            if (!got.ok() || !got.data().toString().contains("Editor Gem")) {
                problems.add("browse.get did not return the saved document over the relay");
            }

            // 7. the relay session counts as active; the user surface closes it
            problems.addAll(drive(console, log, options, "materione editor status",
                    "editor transport: running (1 active editor(s)"));
            console.send("materione editor stop");
            if (log.awaitLine("MATERIONE-EDITOR-STOPPED", options.commandTimeout(), log.mark()) == null) {
                problems.add("MATERIONE-EDITOR-STOPPED missing after 'materione editor stop'");
            }

            String fullLog = log.readAll();
            if (fullLog != null && fullLog.contains("MTR-0050")) {
                problems.add("relay-unreachable fallback fired (MTR-0050) — the gate must prove the cloud path");
            }

            console.send("stop");
            if (await(log, "MATERIONE-BYE", options.stopTimeout()) == null) {
                problems.add("MATERIONE-BYE missing after stop");
            }
        } catch (Exception e) {
            java.io.StringWriter stack = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(stack));
            problems.add("relay scenario error: " + stack);
        } finally {
            PaperHarnessRunner.killTree(process);
        }
        return finish(process, log, options, problems, workspace);
    }

    // ------------------------------------------------------------------

    /**
     * Sequential mailbox reader for the pre-key phase: strictly one message
     * per {@link #next} (first unconsumed, never "skip to last"), advancing
     * the cursor so the post-handshake pump can take over exactly where the
     * reader stopped — retained mailbox frames are the buffer between them.
     */
    private static final class MailboxReader {
        private final RelayClient client;
        private final String channelId;
        private final String token;
        private long cursor = 0;

        MailboxReader(RelayClient client, String channelId, String token) {
            this.client = client;
            this.channelId = channelId;
            this.token = token;
        }

        String next() throws Exception {
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                RelayClient.Poll poll = client.poll(channelId, token, cursor, 2);
                if (!poll.messages().isEmpty()) {
                    RelayClient.Delivered first = poll.messages().get(0);
                    cursor = first.id();
                    return first.messageText();
                }
            }
            throw new AssertionError("no mailbox message arrived in time");
        }

        long cursor() {
            return cursor;
        }
    }

    /**
     * The sealed-RPC browser over the mailbox: a pump thread drains p2e
     * frames in strict order from where the {@link MailboxReader} stopped
     * (the mailbox retains everything, so nothing between reader and pump is
     * lost), routing responses to waiters and pushes to the queue — the
     * relay twin of the direct-mode BrowserClient.
     */
    private static final class RelayBrowser {
        private final RelayClient client;
        private final String channelId;
        private final String token;
        private final SecretKey sessionKey;
        private final java.security.PrivateKey editorSigning;
        private final java.security.PublicKey pluginSigning;
        private final FrameCodec sender = FrameCodec.sender();
        private final FrameCodec receiver = FrameCodec.receiver();
        private final java.util.Map<String, CompletableFuture<Wire.Response>> waiters =
                new ConcurrentHashMap<>();
        private final BlockingQueue<Wire.Request> pushes = new LinkedBlockingQueue<>();
        private volatile Exception pumpFailure;
        private long nextId;

        RelayBrowser(
                RelayClient client, String channelId, String token, MailboxReader reader,
                SecretKey sessionKey, java.security.PrivateKey editorSigning,
                java.security.PublicKey pluginSigning) {
            this.client = client;
            this.channelId = channelId;
            this.token = token;
            this.sessionKey = sessionKey;
            this.editorSigning = editorSigning;
            this.pluginSigning = pluginSigning;
            Thread pump = new Thread(() -> {
                long cursor = reader.cursor();
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        RelayClient.Poll poll = client.poll(channelId, token, cursor, 2);
                        if (poll.closed()) {
                            return;
                        }
                        for (RelayClient.Delivered delivered : poll.messages()) {
                            cursor = delivered.id();
                            byte[] body = receiver.open(
                                    sessionKey, pluginSigning, Wire.parseObject(delivered.messageText()));
                            JsonObject message = Wire.parseObject(
                                    new String(body, StandardCharsets.UTF_8));
                            if (message.has("ok")) {
                                var waiter = waiters.remove(message.get("id").getAsString());
                                if (waiter != null) {
                                    waiter.complete(Wire.Response.fromJson(message));
                                }
                            } else if (message.has("op")) {
                                pushes.offer(Wire.Request.fromJson(message));
                            }
                        }
                    }
                } catch (Exception e) {
                    pumpFailure = e;
                }
            }, "harness-relay-pump");
            pump.setDaemon(true);
            pump.start();
        }

        Wire.Response call(String op, JsonObject payload) throws Exception {
            String id = "c" + (++nextId);
            CompletableFuture<Wire.Response> future = new CompletableFuture<>();
            waiters.put(id, future);
            JsonObject request = new JsonObject();
            request.addProperty("op", op);
            request.addProperty("id", id);
            request.add("payload", payload);
            client.postMessages(channelId, token, List.of(Wire.canonicalJson(
                    sender.seal(sessionKey, editorSigning, Wire.canonicalBytes(request)))));
            try {
                return future.get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "relay RPC '" + op + "' failed; pump failure: " + pumpFailure, e);
            }
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
    }

    private HarnessResult finish(
            Process process, LogWatcher log, HarnessOptions options, List<String> problems,
            ServerWorkspace workspace) {
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new HarnessResult(problems.isEmpty(), problems, -1, workspace.root, List.of());
    }

    private List<String> drive(ConsoleDriver console, LogWatcher log, HarnessOptions options,
            String command, String marker) throws Exception {
        int cursor = log.mark();
        console.send(command);
        if (log.awaitLine(marker, options.commandTimeout(), cursor) == null) {
            return List.of("marker '" + marker + "' missing after '" + command + "'");
        }
        return List.of();
    }

    private static String await(LogWatcher log, String marker, java.time.Duration timeout)
            throws InterruptedException {
        return log.awaitLine(marker, timeout);
    }

    private static HarnessResult failure(List<String> problems, int exit, ServerWorkspace workspace) {
        return new HarnessResult(false, problems, exit,
                workspace == null ? null : workspace.root, List.of());
    }

    /** Alternating key/value pairs; numeric strings become ints. */
    private static JsonObject object(String... pairs) {
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
}
