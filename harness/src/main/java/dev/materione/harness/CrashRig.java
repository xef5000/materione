package dev.materione.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The crash rig of gui-input-durability.md section 11/12 (row R, #306; row H, #313 builds on it): one server workspace
 * that can be booted, killed at a <b>named crash gate</b> and booted again <b>on the same world</b>, with the player's
 * saved data readable from the harness side between the two.
 *
 * <p>The server runs the test-only {@code durabilityProbe} plugin (a fake-connection player and the gate command, see
 * {@code DurabilityProbe}); the product plugin is installed too, as in every other scenario, so a fake player joins a
 * server that runs Materione's own join handlers. A gate is a console command that logs
 * {@code MATERIONE-DPROBE gate reached=<name>} and parks the main thread; {@link #killAtGate} sees the marker and
 * {@code destroyForcibly}s the whole process tree, which is a {@code kill -9} at exactly that instant (nothing ticks
 * once the thread is parked, so no autosave can run after the marker).
 */
public final class CrashRig implements AutoCloseable {

    /** The prefix every probe answer carries. */
    public static final String MARKER = "MATERIONE-DPROBE ";

    private final HarnessOptions options;
    private final Path serverJar;
    private final ServerWorkspace workspace;
    private final LogWatcher log;
    private final String readyMarker;
    private Process process;
    private ConsoleDriver console;
    private int boots;

    private CrashRig(HarnessOptions options, Path serverJar, ServerWorkspace workspace, String readyMarker) {
        this.options = options;
        this.serverJar = serverJar;
        this.workspace = workspace;
        this.readyMarker = readyMarker;
        this.log = new LogWatcher(workspace.logFile);
    }

    /**
     * A fresh workspace with the product plugin (no content packs: the rig tests the platform, not the pipeline) and the
     * durability probe. {@code paperGlobalYaml} (nullable) is written to {@code config/paper-global.yml} before the first
     * boot, e.g. to turn the Paper player autosave on.
     */
    public static CrashRig create(HarnessOptions options, String scenario, String paperGlobalYaml) throws IOException {
        return create(options, options.paperJar(), scenario, paperGlobalYaml, "harness.durabilityProbeJar",
                MARKER + "ready", true);
    }

    /**
     * The general form: any server jar (Paper or Folia), the system property naming the probe plugin jar, the log line
     * that says the probe enabled, and whether the product plugin is installed (the Folia probe runs without it:
     * Materione does not declare {@code folia-supported}).
     */
    public static CrashRig create(HarnessOptions options, Path serverJar, String scenario, String paperGlobalYaml,
            String probeJarProperty, String readyMarker, boolean installProduct) throws IOException {
        Path probe = Path.of(System.getProperty(probeJarProperty, ""));
        if (!Files.isRegularFile(probe)) {
            throw new IOException(probeJarProperty + " is not set or missing: run via :harness:integrationTest");
        }
        ServerWorkspace workspace = ServerWorkspace.create(options, scenario, Set.of());
        if (!installProduct) {
            Files.delete(workspace.pluginsDir.resolve("Materione.jar"));
        }
        Files.copy(probe, workspace.pluginsDir.resolve("MaterioneDurabilityProbe.jar"),
                StandardCopyOption.REPLACE_EXISTING);
        if (paperGlobalYaml != null) {
            Path config = workspace.root.resolve("config");
            Files.createDirectories(config);
            Files.writeString(config.resolve("paper-global.yml"), paperGlobalYaml, StandardCharsets.UTF_8);
        }
        return new CrashRig(options, serverJar, workspace, readyMarker);
    }

    public ServerWorkspace workspace() {
        return workspace;
    }

    public LogWatcher log() {
        return log;
    }

    /** How many times the server has been started in this workspace. */
    public int boots() {
        return boots;
    }

    /**
     * Starts the server on the existing workspace and waits for the probe's ready marker. The previous boot's
     * {@code latest.log} is moved aside first, so a marker from an earlier boot can never satisfy a wait in this one.
     */
    public void boot() throws IOException, InterruptedException {
        if (process != null && process.isAlive()) {
            throw new IllegalStateException("the server is already running");
        }
        moveLogAside();
        boots++;
        process = new ProcessBuilder(options.javaExecutable().toString(), "-Xmx2G", "-Dfile.encoding=UTF-8",
                "-DIReallyKnowWhatIAmDoingISwear", "-jar", serverJar.toString(), "nogui")
                .directory(workspace.root.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(workspace.stdoutFile.toFile())).start();
        console = new ConsoleDriver(process.getOutputStream());
        if (log.awaitLine("Done (", options.bootTimeout()) == null) {
            throw new IllegalStateException("boot " + boots + ": the server did not finish booting\n" + tail());
        }
        if (log.awaitLine(readyMarker, options.commandTimeout()) == null) {
            throw new IllegalStateException("boot " + boots + ": the durability probe did not enable\n" + tail());
        }
    }

    /**
     * Windows releases a killed process's file handles a moment after the process is gone, so the move is retried; a log
     * that cannot be moved at all fails the boot rather than letting an old marker satisfy a wait.
     */
    private void moveLogAside() throws IOException, InterruptedException {
        for (int attempt = 0; Files.exists(workspace.logFile); attempt++) {
            try {
                Files.move(workspace.logFile, workspace.logFile.resolveSibling("boot-" + boots + ".log"),
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException locked) {
                if (attempt >= 40) {
                    throw locked;
                }
                Thread.sleep(500);
            }
        }
    }

    /**
     * Sends a console command and returns the first log line, written after the send, that contains
     * {@code awaitFragment}; fails with the log tail when none arrives within the command timeout.
     */
    public String command(String command, String awaitFragment) throws IOException, InterruptedException {
        int cursor = log.mark();
        console.send(command);
        String line = log.awaitNewLine(awaitFragment, options.commandTimeout(), cursor);
        if (line == null) {
            throw new IllegalStateException("no '" + awaitFragment + "' after '" + command + "'\n" + tail());
        }
        return line;
    }

    /** Sends a console command without waiting for any answer (the caller reads the log itself). */
    public void send(String command) throws IOException {
        console.send(command);
    }

    /** {@code dprobe <args>} answered by a line {@code MATERIONE-DPROBE <stage> ...}; fails on an {@code ok=false} answer. */
    public String probe(String stage, String args) throws IOException, InterruptedException {
        String line = command("dprobe " + stage + (args.isEmpty() ? "" : " " + args), MARKER + stage + " ");
        if (line.contains("ok=false")) {
            throw new IllegalStateException("dprobe " + stage + " failed: " + line + "\n" + tail());
        }
        return line;
    }

    /**
     * The crash: runs the named gate (the main thread parks after logging the marker), waits for the marker, then kills
     * the process tree and waits for it to die. After this returns the world and player data are exactly what the kill
     * left on disk.
     */
    public void killAtGate(String gate) throws IOException, InterruptedException {
        int cursor = log.mark();
        console.send("dprobe gate " + gate);
        if (log.awaitNewLine(MARKER + "gate reached=" + gate, options.commandTimeout(), cursor) == null) {
            throw new IllegalStateException("gate '" + gate + "' was not reached\n" + tail());
        }
        kill();
    }

    /** {@code kill -9}: the whole tree, no shutdown hook, no final save. */
    public void kill() throws InterruptedException {
        PaperHarnessRunner.killTree(process);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the killed server did not exit");
        }
    }

    /** A graceful {@code stop}: the server saves every online player (F7) and exits. Returns the exit code. */
    public int stop() throws IOException, InterruptedException {
        console.send("stop");
        if (!process.waitFor(options.stopTimeout().toSeconds(), TimeUnit.SECONDS)) {
            PaperHarnessRunner.killTree(process);
            throw new IllegalStateException("the server did not stop in time\n" + tail());
        }
        return process.exitValue();
    }

    public boolean alive() {
        return process != null && process.isAlive();
    }

    /** The offline-mode UUID the probe gives a fake player, so a restarted server finds the same {@code .dat}. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public Path playerDataFile(String name) {
        return workspace.root.resolve("world").resolve("playerdata").resolve(offlineUuid(name) + ".dat");
    }

    public Path playerDataDir() {
        return workspace.root.resolve("world").resolve("playerdata");
    }

    /** The saved player data on disk, read by the harness (not by the server). */
    public PlayerDat savedData(String name) throws IOException {
        return PlayerDat.read(playerDataFile(name));
    }

    public List<String> tailLines() {
        return log.tail(60);
    }

    private String tail() {
        return String.join("\n", log.tail(40));
    }

    @Override
    public void close() {
        try {
            if (console != null) {
                console.close();
            }
        } catch (IOException ignored) {
            // the process is being torn down anyway
        }
        if (process != null && process.isAlive()) {
            PaperHarnessRunner.killTree(process);
        }
    }

    /** Appends to a file in the workspace; for tests that edit config between boots. */
    public void append(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public Duration commandTimeout() {
        return options.commandTimeout();
    }
}
