package dev.materione.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Boots the pinned Paper server with the built plugin, drives the console,
 * and asserts the stable log markers (harness/README.md). Failures surface
 * as problems on the {@link HarnessResult}; timeouts kill the process tree
 * (descendants first — forked watchdogs otherwise survive on Windows) and
 * preserve the workspace for inspection.
 */
public final class PaperHarnessRunner {

    /** Runs the standard scenario: boot → version → doctor → stop. */
    public HarnessResult run(HarnessOptions options, Set<String> toleratedErrorMarkers) {
        ServerWorkspace workspace;
        try {
            workspace = ServerWorkspace.create(options, "bootstrap");
        } catch (IOException e) {
            return failed(List.of("could not create workspace: " + e), -1, options.workRoot(), List.of());
        }

        LogWatcher log = new LogWatcher(workspace.logFile);
        LogWatcher stdout = new LogWatcher(workspace.stdoutFile);

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
            return failed(List.of("could not start server: " + e), -1, workspace.root, List.of());
        }

        List<String> problems = new ArrayList<>();
        try (ConsoleDriver console = new ConsoleDriver(process.getOutputStream())) {
            String bootLine = await(log, "Done (", options.bootTimeout());
            if (bootLine == null) {
                if (!process.isAlive()) {
                    problems.add("server died during boot (exit " + exitCode(process) + ")");
                } else {
                    problems.add("boot deadline exceeded (" + options.bootTimeout().toSeconds() + "s)");
                }
                return failed(problems, exitCode(process), workspace.root, tails(log, stdout));
            }

            if (log.readAll() == null || !log.readAll().contains("MATERIONE-BOOT-OK")) {
                problems.add("MATERIONE-BOOT-OK missing after boot");
            }
            if (log.readAll() == null || !log.readAll().contains("MATERIONE-CONTENT-OK items=53")) {
                problems.add("MATERIONE-CONTENT-OK items=53 missing after boot "
                        + "(fixture packs: 3 sample + 50 corpus items should be live)");
            }
            if (log.readAll() == null || !log.readAll().contains("MATERIONE-DELIVERY-OK")) {
                problems.add("MATERIONE-DELIVERY-OK missing after boot (self-host pack server)");
            }

            problems.addAll(drive(console, log, options, "materione version",
                    "MATERIONE-VERSION " + options.expectedVersion()));
            problems.addAll(drive(console, log, options, "materione doctor",
                    "MATERIONE-DOCTOR-OK"));
            // no-op reload first: nothing changed since boot
            problems.addAll(drive(console, log, options, "materione reload",
                    "MATERIONE-RELOAD-OK items=53"));
            hotReloadScenario(console, log, workspace, options, problems);

            console.send("stop");
            String bye = await(log, "MATERIONE-BYE", options.stopTimeout());
            if (bye == null) {
                problems.add("MATERIONE-BYE missing after stop");
            }
            if (!waitForExit(process, options.stopTimeout())) {
                problems.add("server did not exit within " + options.stopTimeout().toSeconds() + "s of stop");
            }
        } catch (IOException e) {
            problems.add("console I/O failure: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problems.add("interrupted while waiting for markers");
        } finally {
            killTree(process);
        }

        int exit = exitCode(process);
        if (exit != 0) {
            problems.add("non-zero exit code: " + exit);
        }
        problems.addAll(attributableErrors(log, toleratedErrorMarkers));
        return new HarnessResult(problems.isEmpty(), problems, exit, workspace.root, tails(log, stdout));
    }

    /**
     * The M1 exit-gate scenario, live on Paper: add an item → reload (hot
     * reload with a real file change), drop a syntactically broken file →
     * reload (fault injection: MTR-0007, everything else stays live), remove
     * both → reload (back to the boot state). The 1s pauses let each
     * scheduled apply swap land before the next reload's no-op check.
     */
    private void hotReloadScenario(ConsoleDriver console, LogWatcher log, ServerWorkspace workspace,
            HarnessOptions options, List<String> problems) throws IOException, InterruptedException {
        Path corpusItems = workspace.root
                .resolve("plugins").resolve("Materione")
                .resolve("content").resolve("packs").resolve("corpus").resolve("items");
        Path faultSource = options.contentPacksDir() == null
                ? null
                : options.contentPacksDir().getParent().resolve("faults").resolve("hot-reload-broken.yml");
        if (faultSource == null || !Files.isDirectory(corpusItems) || !Files.isRegularFile(faultSource)) {
            problems.add("hot-reload scenario skipped: corpus pack or fault fixture missing");
            return;
        }

        Path extra = corpusItems.resolve("gem_extra.yml");
        Path broken = corpusItems.resolve("gem_extra_broken.yml");

        Files.writeString(extra, """
                gem_extra:
                  material: DIAMOND
                  texture: corpus/red
                  display_name: "Extra Gem"
                """);
        problems.addAll(drive(console, log, options, "materione reload",
                "MATERIONE-RELOAD-OK items=54"));

        Thread.sleep(1000);
        Files.writeString(broken, Files.readString(faultSource));
        int beforeFaultReload = log.mark();
        problems.addAll(drive(console, log, options, "materione reload",
                "MATERIONE-RELOAD-OK items=54"));
        if (log.awaitLine("MTR-0007", java.time.Duration.ofSeconds(5), beforeFaultReload) == null) {
            problems.add("fault injection: MTR-0007 missing after reloading a broken item file");
        }

        Thread.sleep(1000);
        Files.deleteIfExists(extra);
        Files.deleteIfExists(broken);
        problems.addAll(drive(console, log, options, "materione reload",
                "MATERIONE-RELOAD-OK items=53"));
    }

    private List<String> drive(ConsoleDriver console, LogWatcher log, HarnessOptions options,
            String command, String marker) throws IOException, InterruptedException {
        int cursor = log.mark(); // only lines from now on may satisfy the marker
        console.send(command);
        String line = await(log, marker, options.commandTimeout(), cursor);
        if (line == null) {
            return List.of("marker '" + marker + "' missing after '" + command
                    + "' (" + options.commandTimeout().toSeconds() + "s)");
        }
        return List.of();
    }

    private static String await(LogWatcher log, String marker, java.time.Duration timeout, int cursor)
            throws InterruptedException {
        return log.awaitLine(marker, timeout, cursor);
    }

    /**
     * Lines attributable to Materione at SEVERE/ERROR level that the scenario
     * does not tolerate (fail-soft scenarios tolerate their expected codes).
     */
    private static List<String> attributableErrors(LogWatcher log, Set<String> tolerated) {
        List<String> found = new ArrayList<>();
        String content = log.readAll();
        if (content == null) {
            return found;
        }
        for (String line : content.split("\n", -1)) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("materione") && (line.contains("SEVERE") || line.contains("/ERROR]"))) {
                boolean toleratedLine = tolerated.stream().anyMatch(line::contains);
                if (!toleratedLine) {
                    found.add("attributable error log: " + line.trim());
                }
            }
        }
        return found;
    }

    private static String await(LogWatcher log, String marker, java.time.Duration timeout)
            throws InterruptedException {
        return log.awaitLine(marker, timeout);
    }

    private static boolean waitForExit(Process process, java.time.Duration timeout)
            throws InterruptedException {
        return process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
    }

    private static int exitCode(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    private static void killTree(Process process) {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static List<String> tails(LogWatcher log, LogWatcher stdout) {
        List<String> tail = new ArrayList<>(stdout.tail(50));
        tail.addAll(log.tail(50));
        return tail;
    }

    private static HarnessResult failed(List<String> problems, int exit, Path workDir, List<String> tail) {
        return new HarnessResult(false, problems, exit, workDir, tail);
    }
}
