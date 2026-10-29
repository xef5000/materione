package dev.materione.harness;

import static dev.materione.harness.EditorBrowser.object;

import com.google.gson.JsonObject;
import dev.materione.web.protocol.Wire;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

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

            // 1–5. the handshake, played the way a browser plays it (EditorBrowser)
            EditorBrowser.BrowserClient browser = EditorBrowser.connect(console, log, options, problems);
            if (browser == null) {
                return finish(process, log, options, problems, workspace);
            }

            // 6. sealed ping over the wire, sealed pong back, then document
            // ops: create an item THROUGH THE EDITOR API and watch the live
            // preview loop take it in-game — M3's promise in miniature.
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

            // M4: author a glyph through the editor. Its own texture (the
            // editor_gem one is renamed later in the scenario — a moved
            // texture would quarantine the glyph mid-run), the save goes live
            // through the same preview loop, and the allocated codepoint
            // surfaces as server-computed browse metadata — never inside the
            // file itself.
            Wire.Response glyphTexture = browser.call("assets.put", object(
                    "pack", "sample",
                    "path", "assets/textures/harness_heart.png",
                    "data", ONE_BY_ONE_PNG_BASE64));
            if (!glyphTexture.ok()) {
                problems.add("assets.put (glyph texture) failed: " + glyphTexture.error());
            }
            Wire.Response glyphCreate = browser.call("edit.create", object(
                    "pack", "sample",
                    "path", "glyphs/harness_heart.yml",
                    "id", "harness_heart"));
            if (!glyphCreate.ok()) {
                problems.add("edit.create (glyph) failed: " + glyphCreate.error());
            }
            Wire.Response glyphPut = browser.call("edit.put", object(
                    "pack", "sample",
                    "path", "glyphs/harness_heart.yml",
                    "rev", "1",
                    "content", "# created by the harness\n"
                            + "harness_heart:\n"
                            + "  texture: harness_heart\n"
                            + "  ascent: 8\n"
                            + "  height: 8\n"
                            + "  placeholders:\n"
                            + "    - \":hheart:\"\n"
                            + "  emoji: true\n"));
            if (!glyphPut.ok()) {
                problems.add("glyph edit.put failed: " + glyphPut.error());
            }
            // Allocation is durable before ZIP staging, and push.reload has
            // no save correlation. A queued console reload is a FIFO barrier
            // for the preceding editor mutations, including their apply.
            int glyphBarrier = log.mark();
            console.send("materione reload");
            if (log.awaitLine("MATERIONE-RELOAD-OK", options.commandTimeout(), glyphBarrier) == null) {
                problems.add("glyph reload completion barrier missing");
                return finish(process, log, options, problems, workspace);
            }
            Wire.Response glyphGet = browser.call("browse.get", object(
                    "pack", "sample", "path", "glyphs/harness_heart.yml"));
            String computedData = glyphGet.ok() ? glyphGet.data().toString() : "";
            boolean computedSeen = computedData.contains("\"computed\"");
            if (!computedSeen) {
                problems.add("browse.get missing computed glyph metadata (the allocated codepoint)");
            } else {
                // runtime↔artifact consistency: the computed codepoint's char
                // must appear in the staged font's chars — a mismatch means
                // chat renders the missing-glyph box even though the
                // placeholder "resolved"
                var charMatcher = java.util.regex.Pattern
                        .compile("\"char\":\"(?:\\\\u([0-9a-fA-F]{4})|([\\s\\S]))\"")
                        .matcher(computedData);
                if (!charMatcher.find()) {
                    problems.add("computed glyph metadata carries no char: " + computedData);
                } else {
                    char computedChar = charMatcher.group(1) != null
                            ? (char) Integer.parseInt(charMatcher.group(1), 16)
                            : charMatcher.group(2).charAt(0);
                    try (ZipFile stagedGlyphPack = new ZipFile(workspace.root
                            .resolve("plugins/Materione/generated/pack/pack.zip").toFile())) {
                        var stagedFont = stagedGlyphPack.getEntry("assets/sample/font/glyphs.json");
                        String fontChars = stagedFont == null
                                ? ""
                                : new String(stagedGlyphPack.getInputStream(stagedFont).readAllBytes(),
                                        StandardCharsets.UTF_8);
                        if (!fontChars.contains(String.valueOf(computedChar))) {
                            problems.add("runtime glyph codepoint " + (int) computedChar
                                    + " missing from staged font chars (chat would render tofu)");
                        }
                    }
                }
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
            // and the M4 glyph document + its texture
            Wire.Response glyphForDelete = browser.call("browse.get", object(
                    "pack", "sample", "path", "glyphs/harness_heart.yml"));
            if (glyphForDelete.ok()) {
                browser.call("edit.delete", object(
                        "pack", "sample", "path", "glyphs/harness_heart.yml",
                        "rev", glyphForDelete.data().getAsJsonObject().get("rev").getAsString()));
            }
            browser.call("assets.delete", object(
                    "pack", "sample", "path", "assets/textures/harness_heart.png"));
            Thread.sleep(800);
            browser.close();

            // 7. prove the pack is back to its boot state, then stop
            // (3 sample + 50 corpus items + 2 auto placement items)
            problems.addAll(drive(console, log, options, "materione reload",
                    "MATERIONE-RELOAD-OK items=57"));
            problems.addAll(drive(console, log, options, "materione editor status",
                    "editor transport: running"));
            int stopCursor = log.mark();
            console.send("materione editor stop");
            if (log.awaitLine("MATERIONE-EDITOR-STOPPED", options.commandTimeout(), stopCursor) == null) {
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
        return new HarnessResult(problems.isEmpty(), problems, -1, workspace.root, log.tail(60));
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

    /** UTF-8 text as the base64 payload assets.put expects. */
    private static String base64(String text) {
        return java.util.Base64.getEncoder()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
