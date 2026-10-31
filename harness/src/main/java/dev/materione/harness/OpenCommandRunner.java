package dev.materione.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The open-command gate, live on Paper (logic.md §6.1, issue #96, #215): a GUI that declares {@code open:
 * commands:} gets a Brigadier literal at boot, a bad argument is answered with a usage line, a content reload
 * adds a new alias to the live tree and hides a removed one, and both without a restart.
 *
 * <p>Headless by construction: no client joins, so the console is the sender. A console has no window, so
 * the scenario proves the registration, the typed parse and the usage path, and the reload seam; the window
 * itself, the requirement and the deny flow need a player and are judged by the unit tiers and the QA catalog
 * ({@code qa/catalog/opencommands.mjs}).
 */
public final class OpenCommandRunner {

    private static final String GUI_PATH = "guis/open_probe.yml";

    /** The GUI as the scenario authors it: one open alias with one choice argument. */
    static String guiYaml(String id, String alias) {
        return """
                %s:
                  title: Probe
                  rows: 1
                  layers: []
                  open:
                    commands: [%s]
                    args:
                      - { name: category, type: choice, default: all, suggest: [all, tools] }
                """.formatted(id, alias);
    }

    public HarnessResult run(HarnessOptions options) {
        ServerWorkspace workspace;
        try {
            workspace = ServerWorkspace.create(options, "opencmd", Set.of("sample", "corpus", "guilogic"));
            Path guis = workspace.root.resolve("plugins/Materione/content/packs/sample/guis");
            Files.createDirectories(guis);
            Files.writeString(guis.resolve("open_probe.yml"), guiYaml("probe_open", "probe_open"));
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
            // 1. the boot registered the declared alias: the console runs it and the executor answers
            problems.addAll(expect(console, log, options, "probe_open",
                    "MATERIONE-OPEN-COMMAND alias=probe_open outcome=console"));
            // 2. a value the block does not allow is a usage answer from the server, not a crash
            problems.addAll(expect(console, log, options, "probe_open nope",
                    "MATERIONE-OPEN-COMMAND alias=probe_open outcome=usage"));
            // 3. the author renames the command and reloads: the new alias is added without a restart
            Files.writeString(workspace.root.resolve("plugins/Materione/content/packs/sample/" + GUI_PATH),
                    guiYaml("probe_two", "probe_two"));
            problems.addAll(expect(console, log, options, "materione reload", "MATERIONE-RELOAD-OK"));
            problems.addAll(expect(console, log, options, "probe_two",
                    "MATERIONE-OPEN-COMMAND alias=probe_two outcome=console"));
            // 4. the old alias is no longer declared: it stops running. Commands run in order, so the marker of the
            // command sent after it proves the old one was already processed.
            int cursor = log.mark();
            console.send("probe_open");
            problems.addAll(expect(console, log, options, "probe_two",
                    "MATERIONE-OPEN-COMMAND alias=probe_two outcome=console", cursor));
            if (log.readAll().substring(cursor).contains("alias=probe_open")) {
                problems.add("probe_open still ran after a reload removed it");
            }
            console.send("stop");
            if (log.awaitLine("MATERIONE-BYE", options.stopTimeout()) == null) {
                problems.add("MATERIONE-BYE missing after stop");
            }
        } catch (Exception e) {
            java.io.StringWriter stack = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(stack));
            problems.add("open command scenario error: " + stack);
        } finally {
            PaperHarnessRunner.killTree(process);
        }
        return finish(process, log, problems, workspace);
    }

    /** Runs one console command and waits for the marker it prints, from the start of the log. */
    private static List<String> expect(ConsoleDriver console, LogWatcher log, HarnessOptions options,
            String command, String marker) throws Exception {
        return expect(console, log, options, command, marker, log.mark());
    }

    /** Runs one console command and waits for the marker it prints at or after {@code cursor}. */
    private static List<String> expect(ConsoleDriver console, LogWatcher log, HarnessOptions options,
            String command, String marker, int cursor) throws Exception {
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
