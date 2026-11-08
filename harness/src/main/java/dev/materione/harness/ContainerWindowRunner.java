package dev.materione.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The window type and top size of the two non-chest containers gui.md section 12 builds on, on the
 * 1.21.4 floor (#289, row R). A test-only plugin ({@code harness/src/containerProbe}) creates a hopper and
 * a dispenser through the legacy path, {@code createInventory(holder, InventoryType, Component)}, with a
 * holder of its own and a title component, and reports what the server maps them to: the inventory type,
 * its top size, the menu type the client is told (the registry key {@code CraftContainer} resolves, which is
 * what the open-screen packet carries) and that the holder survives. Headless by construction: no client
 * joins, so the screen itself (title position, art placement) is the 26.1.2 probe's evidence (gui.md 12.2.6).
 */
public final class ContainerWindowRunner {

    public HarnessResult run(HarnessOptions options) {
        ServerWorkspace workspace;
        try {
            Path probe = Path.of(System.getProperty("harness.containerProbeJar", ""));
            if (!Files.isRegularFile(probe)) {
                throw new IOException("harness.containerProbeJar is not set or missing: run via :harness:integrationTest");
            }
            workspace = ServerWorkspace.create(options, "container-window", Set.of("corpus"));
            Files.copy(probe, workspace.pluginsDir.resolve("MaterioneContainerProbe.jar"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return new HarnessResult(false, List.of("could not create workspace: " + e), -1, null, List.of());
        }
        LogWatcher log = new LogWatcher(workspace.logFile);
        Process process;
        try {
            process = new ProcessBuilder(options.javaExecutable().toString(), "-Xmx2G", "-Dfile.encoding=UTF-8",
                    "-DIReallyKnowWhatIAmDoingISwear", "-jar", options.paperJar().toString(), "nogui")
                    .directory(workspace.root.toFile()).redirectErrorStream(true)
                    .redirectOutput(workspace.stdoutFile.toFile()).start();
        } catch (IOException e) {
            return new HarnessResult(false, List.of("could not start server: " + e), -1, workspace.root, List.of());
        }

        List<String> problems = new ArrayList<>();
        try (ConsoleDriver console = new ConsoleDriver(process.getOutputStream())) {
            if (log.awaitLine("Done (", options.bootTimeout()) == null) {
                problems.add("boot deadline exceeded");
                return finish(process, log, problems, workspace);
            }
            if (log.awaitLine("MATERIONE-CTPROBE ready", options.commandTimeout()) == null) {
                problems.add("the container probe plugin did not enable");
                return finish(process, log, problems, workspace);
            }
            // The hopper: five slots, the hopper menu. The dispenser: nine slots, the 3 x 3 menu (the
            // dropper's too: one menu, one screen). The names are the registry keys of the menu types.
            problems.addAll(describe(console, log, options, "hopper", "HOPPER", 5, "minecraft:hopper"));
            problems.addAll(describe(console, log, options, "dispenser", "DISPENSER", 9, "minecraft:generic_3x3"));

            console.send("stop");
            if (log.awaitLine("MATERIONE-BYE", options.stopTimeout()) == null) {
                problems.add("MATERIONE-BYE missing after stop");
            }
        } catch (Exception e) {
            problems.add("container window scenario error: " + e);
        } finally {
            PaperHarnessRunner.killTree(process);
        }
        return finish(process, log, problems, workspace);
    }

    private static List<String> describe(ConsoleDriver console, LogWatcher log, HarnessOptions options,
            String container, String type, int size, String menuType) throws Exception {
        int cursor = log.mark();
        console.send("ctprobe describe " + container);
        String line = log.awaitLine("MATERIONE-CTPROBE describe kind=" + container + " ", options.commandTimeout(), cursor);
        if (line == null) {
            return List.of("no answer to 'ctprobe describe " + container + "'");
        }
        List<String> problems = new ArrayList<>();
        for (String expected : List.of("ok=true", "type=" + type, "size=" + size, "menuType=" + menuType,
                "holder=true")) {
            if (!line.contains(" " + expected)) {
                problems.add(container + ": expected '" + expected + "' in: " + line);
            }
        }
        return problems;
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
