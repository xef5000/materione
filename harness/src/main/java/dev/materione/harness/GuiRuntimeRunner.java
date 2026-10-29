package dev.materione.harness;

import static dev.materione.harness.EditorBrowser.object;

import com.google.gson.JsonObject;
import dev.materione.web.protocol.Wire;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The M6 runtime gate, live on Paper (gui.md §5, §7): a GUI authored into the sample pack goes live
 * at boot, the console's {@code gui open} surface answers for it, the editor's preview op refuses a
 * session with no approved player, and the editor's own saves drive the live refresh — a changed GUI
 * is refreshed after the apply within the live-loop budget, a GUI that stops compiling retires, and a
 * slot naming an item that is not live is diagnosed while the rest of the GUI stays live.
 *
 * <p>Headless by construction: no client joins, so the window itself (its title component and its
 * item stacks) is judged by the unit tiers ({@code RuntimeGuisTest}, {@code GuiTitleTest}) and by the
 * QA catalog's {@code gui.runtime-*} entries, not here. This scenario proves the seams: the apply, the
 * shadow view, the command and the protocol, and the refresh that a viewer would see.
 */
public final class GuiRuntimeRunner {

    /** The live-loop budget (plugin.md §9): a save must reach the live view within this long. */
    static final Duration LIVE_LOOP_BUDGET = Duration.ofSeconds(2);

    static final String GUI_ID = "sample:runtime_shop";
    private static final String GUI_PATH = "guis/runtime_shop.yml";

    /**
     * The GUI as the scenario authors it: a panel, the container wells, a heading drawn on the window and
     * one item in slot 13. The heading is what the window shows (its compiled title), so it is the field
     * a save changes to move the live view; the {@code title:} key is only the accessible name.
     */
    static String guiYaml(String heading, int rows, String item) {
        return """
                runtime_shop:
                  title: Runtime Shop
                  rows: %d
                  layers:
                    - id: frame
                      type: panel
                      width: 176
                      height: 168
                    - id: wells
                      type: slots
                      region: container
                    - id: heading
                      type: text
                      x: 8
                      y: 6
                      text: "%s"
                      font: smallcaps
                      color: "#404040"
                    - id: sword
                      type: item
                      slot: 13
                      item: %s
                      amount: 1
                """.formatted(rows, heading, item);
    }

    public HarnessResult run(HarnessOptions options) {
        ServerWorkspace workspace;
        try {
            workspace = ServerWorkspace.create(options, "gui", Set.of("sample", "corpus"));
            Path guis = workspace.root.resolve("plugins/Materione/content/packs/sample/guis");
            Files.createDirectories(guis);
            Files.writeString(guis.resolve("runtime_shop.yml"), guiYaml("Shop", 3, "corpus:gem_red"));
        } catch (IOException e) {
            return new HarnessResult(false, List.of("could not create workspace: " + e), -1, null, List.of());
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
        } catch (IOException e) {
            return new HarnessResult(false, List.of("could not start server: " + e), -1, workspace.root, List.of());
        }

        List<String> problems = new ArrayList<>();
        try (ConsoleDriver console = new ConsoleDriver(process.getOutputStream())) {
            if (log.awaitLine("Done (", options.bootTimeout()) == null) {
                problems.add("boot deadline exceeded");
                return finish(process, log, problems, workspace);
            }
            if (!log.readAll().contains("MATERIONE-BOOT-OK")) {
                problems.add("MATERIONE-BOOT-OK missing");
            }

            // 1. the console surface: a GUI that resolves is live (the boot apply made it), and the
            // console has no window to open, so it names a player or is refused
            problems.addAll(expect(console, log, options,
                    "materione gui open runtime_shop",
                    "MATERIONE-GUI-DENIED reason=player-required"));
            problems.addAll(expect(console, log, options,
                    "materione gui open runtime_shop Nobody",
                    "MATERIONE-GUI-FAILED reason=no-such-player"));
            problems.addAll(expect(console, log, options,
                    "materione gui open runtime_missing Nobody",
                    "MATERIONE-GUI-FAILED reason=not-live"));

            // 2. the editor: this console session has no approved player, so there is nothing to preview for
            EditorBrowser.BrowserClient browser = EditorBrowser.connect(console, log, options, problems);
            if (browser == null) {
                return finish(process, log, problems, workspace);
            }
            Wire.Response target = browser.call("runtime.previewTarget", new JsonObject());
            if (!target.ok() || target.data().getAsJsonObject().get("online").getAsBoolean()) {
                problems.add("runtime.previewTarget should report no online player for a console session: "
                        + target.data());
            }
            Wire.Response refused = browser.call("runtime.openGui", object("id", GUI_ID));
            if (refused.ok() || !"MTR-0009".equals(refused.error().code())) {
                problems.add("runtime.openGui without an approved player must be refused with MTR-0009: "
                        + refused.toJson());
            }
            Wire.Response malformed = browser.call("runtime.openGui", object("id", "no-colon"));
            if (malformed.ok() || !"MTR-0008".equals(malformed.error().code())) {
                problems.add("runtime.openGui with a malformed id must be a bad request: " + malformed.toJson());
            }

            // 3. the editor's own save drives the live refresh: a changed GUI is refreshed after the apply
            problems.addAll(saveAndExpect(browser, log, options,
                    guiYaml("Market",3, "corpus:gem_red"),
                    "MATERIONE-GUI-REFRESH changed=1 removed=0"));

            // 4. a GUI that stops compiling is retired: its viewers would be closed with a note
            problems.addAll(saveAndExpect(browser, log, options,
                    guiYaml("Market",9, "corpus:gem_red"),
                    "MATERIONE-GUI-REFRESH changed=0 removed=1"));
            problems.addAll(expect(console, log, options,
                    "materione gui open runtime_shop Nobody",
                    "MATERIONE-GUI-FAILED reason=not-live"));

            // 5. back to life with a slot naming an item that is not live: diagnosed at apply, the GUI stays
            problems.addAll(saveAndExpect(browser, log, options,
                    guiYaml("Market",3, "corpus:not_a_gem"),
                    "MTR-0054"));
            problems.addAll(expect(console, log, options,
                    "materione gui open runtime_shop Nobody",
                    "MATERIONE-GUI-FAILED reason=no-such-player"));

            // 6. the good copy again, then the teardown
            problems.addAll(saveAndExpect(browser, log, options,
                    guiYaml("Shop", 3, "corpus:gem_red"),
                    "MATERIONE-GUI-REFRESH changed=1 removed=0"));
            browser.close();

            console.send("stop");
            if (log.awaitLine("MATERIONE-BYE", options.stopTimeout()) == null) {
                problems.add("MATERIONE-BYE missing after stop");
            }
        } catch (Exception e) {
            java.io.StringWriter stack = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(stack));
            problems.add("gui runtime scenario error: " + stack);
        } finally {
            PaperHarnessRunner.killTree(process);
        }
        return finish(process, log, problems, workspace);
    }

    /**
     * Saves one document of the sample pack through the editor, then waits for the marker the apply
     * writes. The save-to-marker time is held to the live-loop budget.
     */
    private static List<String> saveAndExpect(EditorBrowser.BrowserClient browser, LogWatcher log,
            HarnessOptions options, String content, String marker) throws Exception {
        List<String> problems = new ArrayList<>();
        Wire.Response current = browser.call("browse.get", object("pack", "sample", "path", GUI_PATH));
        if (!current.ok()) {
            problems.add("browse.get " + GUI_PATH + " failed: " + current.toJson());
            return problems;
        }
        String rev = current.data().getAsJsonObject().get("rev").getAsString();
        int cursor = log.mark();
        long started = System.currentTimeMillis();
        Wire.Response put = browser.call("edit.put", object(
                "pack", "sample", "path", GUI_PATH, "rev", rev, "content", content));
        if (!put.ok()) {
            problems.add("edit.put " + GUI_PATH + " failed: " + put.toJson());
            return problems;
        }
        if (log.awaitLine(marker, options.commandTimeout(), cursor) == null) {
            problems.add("'" + marker + "' missing after the editor saved " + GUI_PATH);
            return problems;
        }
        long elapsed = System.currentTimeMillis() - started;
        if (marker.startsWith("MATERIONE-GUI-REFRESH") && elapsed > LIVE_LOOP_BUDGET.toMillis()) {
            problems.add("GUI save reached the live view in " + elapsed + " ms — exceeds the "
                    + LIVE_LOOP_BUDGET.toSeconds() + " s live-loop budget (plugin.md §9)");
        }
        return problems;
    }

    /** Runs one console command and waits for the marker it prints. */
    private static List<String> expect(ConsoleDriver console, LogWatcher log, HarnessOptions options,
            String command, String marker) throws Exception {
        int cursor = log.mark();
        console.send(command);
        if (log.awaitLine(marker, options.commandTimeout(), cursor) == null) {
            return List.of("marker '" + marker + "' missing after '" + command + "'");
        }
        return List.of();
    }

    private static HarnessResult finish(Process process, LogWatcher log, List<String> problems,
            ServerWorkspace workspace) {
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new HarnessResult(problems.isEmpty(), problems, -1, workspace.root, log.tail(60));
    }
}
