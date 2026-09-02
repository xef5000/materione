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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;

/**
 * The M3 handshake gate, live on Paper: boots with the fixture corpus, opens
 * an editor session through the console, then a JDK HTTP/WebSocket client
 * plays the browser end-to-end — capability-URL blob fetch, fragment-key
 * decryption, hello, console-driven trust approval, signed-reply +
 * key-confirmation verification, and one sealed RPC round trip.
 *
 * <p>This runner deliberately reuses {@code dev.materione.web.protocol}
 * classes: it IS a protocol consumer, exactly like the future browser SPA
 * and the M5 Worker tests. Drift anywhere breaks here first.
 */
public final class EditorHandshakeRunner {

    /** A 1×1 transparent PNG — the smallest legal texture for the upload leg. */
    private static final String ONE_BY_ONE_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    public HarnessResult run(HarnessOptions options) {
        ServerWorkspace workspace;
        try {
            workspace = ServerWorkspace.create(options, "editor", Set.of("sample", "corpus"));
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

            // 1. open a session via console, scrape the capability URL
            int cursor = log.mark();
            console.send("materione editor");
            String urlLine = log.awaitLine("MATERIONE-EDITOR http://", options.commandTimeout(), cursor);
            if (urlLine == null) {
                problems.add("MATERIONE-EDITOR url missing after 'materione editor'");
                return finish(process, log, options, problems, workspace);
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
                return finish(process, log, options, problems, workspace);
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
            ws.sendText(Wire.canonicalJson(hello.toJson()), true).get(10, TimeUnit.SECONDS);

            // 4. wait for the trust prompt to surface, THEN answer it from
            // the console — the approve must never race the registration
            int promptCursor = log.mark();
            if (log.awaitLine("wants to connect as editor", options.commandTimeout(), promptCursor) == null) {
                problems.add("trust prompt never surfaced for nonce " + noncePrefix);
            }
            console.send("materione editor approve " + noncePrefix);
            int approvedCursor = log.mark();
            if (log.awaitLine("MTR-0032", options.commandTimeout(), approvedCursor) == null) {
                problems.add("approval did not activate the session (MTR-0032 missing)");
            }

            // 5. signed reply + key confirmation
            JsonObject payload = Wire.parseObject(wsClient.first.get(20, TimeUnit.SECONDS));
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

            // 6. sealed ping over the wire, sealed pong back, then document
            // ops: create an item THROUGH THE EDITOR API and watch the live
            // preview loop take it in-game — M3's promise in miniature.
            BrowserClient browser = new BrowserClient(
                    ws, wsClient, sessionKey, editorSigning.getPrivate(),
                    KeyFormat.ed25519Public(blob.pluginSigningPubKey()));
            Wire.Response pong = browser.call("ping", new JsonObject());
            if (!pong.ok() || !pong.data().toString().contains("pong")) {
                problems.add("ping round trip wrong response: " + pong.data());
            }

            // Save round trip with the plugin.md §9 budget enforced: from
            // send(save) to the push.reload frame announcing the apply —
            // strictly under 2 s.
            long saveStart = System.currentTimeMillis();
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
            Wire.Request reloadPush = browser.awaitPush("push.reload", 15, TimeUnit.SECONDS);
            long roundTrip = System.currentTimeMillis() - saveStart;
            if (reloadPush == null) {
                problems.add("no push.reload after save — live preview loop broken");
            } else if (roundTrip > 2000) {
                problems.add("editor save round-trip took " + roundTrip
                        + " ms — exceeds the 2 s budget (plugin.md §9)");
            }

            // Asset surface: upload a 1×1 PNG, list it back.
            Wire.Response assetPut = browser.call("assets.put", object(
                    "pack", "sample",
                    "path", "assets/textures/editor_gem.png",
                    "data", ONE_BY_ONE_PNG_BASE64));
            if (!assetPut.ok()) {
                problems.add("assets.put failed: " + assetPut.error());
            }
            Wire.Response assetsList = browser.call("assets.list", object("pack", "sample"));
            if (!assetsList.data().toString().contains("editor_gem.png")) {
                problems.add("assets.list did not return the uploaded texture");
            }

            // Scaffolded creation ("New Item" in the explorer, scripted):
            // scaffold content lands at rev 1, duplicates are refused, and
            // the created item goes live through the same preview loop.
            Wire.Response created = browser.call("edit.create", object(
                    "pack", "sample",
                    "path", "items/harness_scaffold.yml",
                    "id", "harness_scaffold"));
            if (!created.ok()) {
                problems.add("edit.create failed: " + created.error());
            } else if (!created.data().toString().contains("material")) {
                problems.add("edit.create scaffold lacks required fields");
            }
            Wire.Response duplicate = browser.call("edit.create", object(
                    "pack", "sample", "path", "items/harness_scaffold.yml"));
            if (duplicate.ok()) {
                problems.add("edit.create clobbered an existing document");
            }

            // Blockbench save-back wire flow (the SPA's "Save to Materione"):
            // exported model JSON + referenced texture + the .bbmodel project
            // source land as assets, then quick-attach wires model: onto the
            // scaffolded item via edit.setScalar — the SPA's exact sequence.
            String bridgeModelJson = "{\"textures\":{\"layer0\":\"sample:item/bridge_gems\"},"
                    + "\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],"
                    + "\"faces\":{\"north\":{\"texture\":\"#layer0\"}}}]}";
            Wire.Response modelPut = browser.call("assets.put", object(
                    "pack", "sample",
                    "path", "assets/models/bridge_gems.json",
                    "data", base64(bridgeModelJson)));
            if (!modelPut.ok()) {
                problems.add("assets.put (bridge model) failed: " + modelPut.error());
            }
            Wire.Response bridgeTexture = browser.call("assets.put", object(
                    "pack", "sample",
                    "path", "assets/textures/item/bridge_gems.png",
                    "data", ONE_BY_ONE_PNG_BASE64));
            if (!bridgeTexture.ok()) {
                problems.add("assets.put (bridge texture) failed: " + bridgeTexture.error());
            }
            Wire.Response bridgeSource = browser.call("assets.put", object(
                    "pack", "sample",
                    "path", "assets/models/source/bridge_gems.bbmodel",
                    "data", base64("{\"meta\":{\"format_version\":\"4.5\"},\"model_format\":\"java_block\"}")));
            if (!bridgeSource.ok()) {
                problems.add("assets.put (bridge source) failed: " + bridgeSource.error());
            }
            int scaffoldRev = created.ok()
                    ? created.data().getAsJsonObject().get("rev").getAsInt() : 0;
            com.google.gson.JsonArray attachField = new com.google.gson.JsonArray();
            attachField.add("harness_scaffold");
            attachField.add("model");
            JsonObject attachPayload = object(
                    "pack", "sample",
                    "path", "items/harness_scaffold.yml",
                    "rev", String.valueOf(scaffoldRev),
                    "value", "sample:bridge_gems");
            attachPayload.add("field", attachField);
            Wire.Response attached = browser.call("edit.setScalar", attachPayload);
            if (!attached.ok()) {
                problems.add("edit.setScalar (model quick-attach) failed: " + attached.error());
            } else {
                scaffoldRev = attached.data().getAsJsonObject().get("rev").getAsInt();
            }

            Thread.sleep(400); // let the queued FileSet apply swap land
            problems.addAll(drive(console, log, options,
                    "materione dev stack sample:harness_scaffold",
                    "MATERIONE-STACK id=sample:harness_scaffold"));

            // Rename via edit.move; the old path must stop resolving.
            Wire.Response moved = browser.call("edit.move", object(
                    "pack", "sample",
                    "from", "items/harness_scaffold.yml",
                    "to", "items/harness_renamed.yml",
                    "rev", String.valueOf(scaffoldRev)));
            if (!moved.ok()) {
                problems.add("edit.move failed: " + moved.error());
            }
            Wire.Response ghost = browser.call("browse.get", object(
                    "pack", "sample", "path", "items/harness_scaffold.yml"));
            if (ghost.ok()) {
                problems.add("old path still readable after edit.move");
            }

            // Assets rename + manual full reload (the control-center op).
            Wire.Response assetMoved = browser.call("assets.move", object(
                    "pack", "sample",
                    "from", "assets/textures/editor_gem.png",
                    "to", "assets/textures/editor/renamed_gem.png"));
            if (!assetMoved.ok()) {
                problems.add("assets.move failed: " + assetMoved.error());
            }
            Wire.Response manual = browser.call("pipeline.reload", object("scope", "everything"));
            if (!manual.ok()) {
                problems.add("pipeline.reload everything failed: " + manual.error());
            }
            // Fire-and-forget by design: the outcome lands as push.reload
            // (manual reloads always announce, skipped ones included).
            Wire.Request manualPush = browser.awaitPush("push.reload", 20, TimeUnit.SECONDS);
            if (manualPush == null) {
                problems.add("no push.reload after manual pipeline.reload");
            }

            // External-edit feed: a hand edit on disk — no editor involved —
            // must journal an external revision and reach connected editors
            // as push.document.changed (the file watcher, configurable).
            java.nio.file.Path handEdited = workspace.pluginsDir
                    .resolve("Materione").resolve("content").resolve("packs")
                    .resolve("sample").resolve("items").resolve("editor_gem.yml");
            java.nio.file.Files.writeString(
                    handEdited,
                    java.nio.file.Files.readString(handEdited) + "# hand-edited by the harness\n",
                    java.nio.charset.StandardCharsets.UTF_8);
            Wire.Request changedPush = browser.awaitPush(
                    "push.document.changed", "\"path\":\"items/editor_gem.yml\"", 30, TimeUnit.SECONDS);
            if (changedPush == null) {
                problems.add("no push.document.changed after a hand edit on disk");
            } else if (!changedPush.payload().toString().contains("\"pack\":\"sample\"")) {
                problems.add("push.document.changed payload wrong: " + changedPush.payload());
            }
            Wire.Response history = browser.call("browse.history", object(
                    "pack", "sample", "path", "items/editor_gem.yml", "limit", "5"));
            if (!history.ok() || !history.data().toString().contains("\"action\":\"external\"")) {
                problems.add("hand edit did not journal an external revision: " + history.data());
            }

            // Settings round trip under the hash guard.
            Wire.Response settingsGet = browser.call("admin.settings.get", new JsonObject());
            if (!settingsGet.ok()) {
                problems.add("admin.settings.get failed: " + settingsGet.error());
            } else {
                JsonObject settings = settingsGet.data().getAsJsonObject();
                Wire.Response settingsPut = browser.call("admin.settings.put", object(
                        "content", settings.get("raw").getAsString(),
                        "sha256", settings.get("sha256").getAsString()));
                if (!settingsPut.ok()) {
                    problems.add("admin.settings.put round trip failed: " + settingsPut.error());
                }
            }

            // The audit trail saw all of it.
            Wire.Response audit = browser.call("admin.audit.recent", object("limit", "100"));
            String auditText = audit.ok() ? audit.data().toString() : "";
            for (String expectedOp : List.of("edit.put", "edit.create", "edit.move",
                    "assets.move", "pipeline.reload", "admin.settings.put")) {
                if (!auditText.contains("\"op\":\"" + expectedOp + "\"")) {
                    problems.add("audit trail missing op " + expectedOp);
                }
            }

            Thread.sleep(500); // let the queued FileSet apply swap land
            problems.addAll(drive(console, log, options,
                    "materione dev stack sample:editor_gem",
                    "MATERIONE-STACK id=sample:editor_gem"));
            Wire.Response got = browser.call("browse.get", object(
                    "pack", "sample", "path", "items/editor_gem.yml"));
            if (!got.ok() || !got.data().toString().contains("Editor Gem")) {
                problems.add("browse.get did not return the saved document");
            }

            int currentRev = got.ok() ? got.data().getAsJsonObject().get("rev").getAsInt() : 0;
            Wire.Response deleted = browser.call("edit.delete", object(
                    "pack", "sample", "path", "items/editor_gem.yml",
                    "rev", String.valueOf(currentRev)));
            if (!deleted.ok()) {
                problems.add("edit.delete failed: " + deleted.error());
            }
            // Remove the scaffold+rename artifacts and the moved texture.
            Wire.Response renamedGet = browser.call("browse.get", object(
                    "pack", "sample", "path", "items/harness_renamed.yml"));
            if (renamedGet.ok()) {
                browser.call("edit.delete", object(
                        "pack", "sample", "path", "items/harness_renamed.yml",
                        "rev", renamedGet.data().getAsJsonObject().get("rev").getAsString()));
            } else {
                problems.add("renamed document missing before cleanup");
            }
            browser.call("assets.delete", object(
                    "pack", "sample", "path", "assets/textures/editor/renamed_gem.png"));
            // and the Blockbench save-back artifacts
            browser.call("assets.delete", object(
                    "pack", "sample", "path", "assets/models/bridge_gems.json"));
            browser.call("assets.delete", object(
                    "pack", "sample", "path", "assets/textures/item/bridge_gems.png"));
            browser.call("assets.delete", object(
                    "pack", "sample", "path", "assets/models/source/bridge_gems.bbmodel"));
            Thread.sleep(800);
            ws.abort();

            // 7. prove the pack is back to its boot state, then stop
            // (3 sample + 50 corpus items + 2 auto placement items)
            problems.addAll(drive(console, log, options, "materione reload",
                    "MATERIONE-RELOAD-OK items=55"));
            problems.addAll(drive(console, log, options, "materione editor status",
                    "editor listener: running"));
            console.send("materione editor stop");
            if (log.awaitLine("MATERIONE-EDITOR-STOPPED", options.commandTimeout(), log.mark()) == null) {
                problems.add("MATERIONE-EDITOR-STOPPED missing after 'materione editor stop'");
            }

            console.send("stop");
            if (await(log, "MATERIONE-BYE", options.stopTimeout()) == null) {
                problems.add("MATERIONE-BYE missing after stop");
            }
        } catch (Exception e) {
            java.io.StringWriter stack = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(stack));
            problems.add("handshake scenario error: " + stack);
        } finally {
            PaperHarnessRunner.killTree(process);
        }
        return finish(process, log, options, problems, workspace);
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
    private final class BrowserClient {
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

        BrowserClient(WebSocket ws, WsClient sink, SecretKey sessionKey,
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

    /** UTF-8 text as the base64 payload assets.put expects. */
    private static String base64(String text) {
        return java.util.Base64.getEncoder()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
