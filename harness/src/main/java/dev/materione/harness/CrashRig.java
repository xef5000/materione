package dev.materione.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
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
 * server that runs Materione's own join handlers. A gate is the product's own {@code CrashGate} (row H: a named point in
 * the escrow code that, under {@code -Dmaterione.escrow.gate}, logs {@code MATERIONE-ESCROW-GATE <name>} and parks the
 * thread it is reached on); {@link #killAtGate} arms one, triggers the step that reaches it, sees the marker and
 * {@code destroyForcibly}s the whole process tree, which is a {@code kill -9} at exactly that instant (the thread is on
 * the main thread, which is parked, so no autosave can run after the marker).
 */
public final class CrashRig implements AutoCloseable {

    /** The prefix every probe answer carries. */
    public static final String MARKER = "MATERIONE-DPROBE ";

    private final HarnessOptions options;
    private final Path serverJar;
    private final ServerWorkspace workspace;
    private final LogWatcher log;
    private final String readyMarker;
    private final List<String> jvmArgs;
    private Process process;
    private ConsoleDriver console;
    private int boots;

    private CrashRig(HarnessOptions options, Path serverJar, ServerWorkspace workspace, String readyMarker,
            List<String> jvmArgs) {
        this.options = options;
        this.serverJar = serverJar;
        this.workspace = workspace;
        this.readyMarker = readyMarker;
        this.jvmArgs = jvmArgs;
        this.log = new LogWatcher(workspace.logFile);
    }

    /** The production crash gate's log line ({@code CrashGate#MARKER}): the name follows. Test API, as the other markers. */
    public static final String GATE_MARKER = "MATERIONE-ESCROW-GATE ";

    /** The JVM property that creates the production gate; its current value is the armed name ({@code off} arms none). */
    public static final String GATE_PROPERTY = "materione.escrow.gate";

    /**
     * The workspace of the kill-boundary IT (row H): the product plugin and the named content packs (the escrow GUI is in
     * {@code guilogic}), the durability probe, and the production crash gate enabled and unarmed.
     */
    public static CrashRig createForEscrow(HarnessOptions options, String scenario, String paperGlobalYaml,
            Set<String> packs) throws IOException {
        return createForEscrow(options, scenario, paperGlobalYaml, packs, null);
    }

    /**
     * As above, with {@code extraSettings} (nullable): flat {@code key: value} lines appended to the product's
     * {@code settings.yml} before the first boot (for example {@code escrow_barrier_cooldown_seconds: 1}).
     */
    public static CrashRig createForEscrow(HarnessOptions options, String scenario, String paperGlobalYaml,
            Set<String> packs, String extraSettings) throws IOException {
        Path probe = Path.of(System.getProperty("harness.durabilityProbeJar", ""));
        if (!Files.isRegularFile(probe)) {
            throw new IOException("harness.durabilityProbeJar is not set or missing: run via :harness:integrationTest");
        }
        ServerWorkspace workspace = ServerWorkspace.create(options, scenario, packs);
        if (extraSettings != null) {
            Path settings = workspace.pluginsDir.resolve("Materione").resolve("settings");
            Files.createDirectories(settings);
            Path file = settings.resolve("settings.yml");
            String head = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "version: 1\n";
            Files.writeString(file, head + extraSettings, StandardCharsets.UTF_8);
        }
        Files.copy(probe, workspace.pluginsDir.resolve("MaterioneDurabilityProbe.jar"),
                StandardCopyOption.REPLACE_EXISTING);
        if (paperGlobalYaml != null) {
            Path config = workspace.root.resolve("config");
            Files.createDirectories(config);
            Files.writeString(config.resolve("paper-global.yml"), paperGlobalYaml, StandardCharsets.UTF_8);
        }
        return new CrashRig(options, options.paperJar(), workspace, MARKER + "ready",
                List.of("-D" + GATE_PROPERTY + "=off"));
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
        return new CrashRig(options, serverJar, workspace, readyMarker, List.of());
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
        // A port another process on the machine holds at that moment (a parallel run, a killed server whose socket is still
        // closing) makes the server exit at once; that is not what a row tests, so the boot is retried a few times.
        for (int attempt = 1; ; attempt++) {
            moveLogAside();
            boots++;
            List<String> command = new ArrayList<>(List.of(options.javaExecutable().toString(), "-Xmx2G",
                    "-Dfile.encoding=UTF-8", "-DIReallyKnowWhatIAmDoingISwear"));
            command.addAll(jvmArgs);
            command.addAll(List.of("-jar", serverJar.toString(), "nogui"));
            process = new ProcessBuilder(command)
                    .directory(workspace.root.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(workspace.stdoutFile.toFile())).start();
            console = new ConsoleDriver(process.getOutputStream());
            long deadline = System.nanoTime() + options.bootTimeout().toNanos();
            boolean done = false;
            while (!done && process.isAlive() && System.nanoTime() < deadline) {
                done = log.awaitLine("Done (", Duration.ofSeconds(2)) != null;
            }
            if (done) {
                break;
            }
            String text = log.readAll();
            boolean bindFailure = text != null && text.contains("FAILED TO BIND");
            if (bindFailure && attempt < 5) {
                PaperHarnessRunner.killTree(process);
                process.waitFor(30, TimeUnit.SECONDS);
                Thread.sleep(15_000);
                continue;
            }
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
     * The crash: arms the production gate {@code gate} (inside the server, the JVM argument cannot change between two
     * steps of one boot), sends the console command that makes the product reach it (the thread it is reached on parks
     * after logging {@code MATERIONE-ESCROW-GATE <gate>}), waits for the marker, then kills the process tree and waits
     * for it to die. After this returns the world and player data are exactly what the kill left on disk.
     *
     * @param trigger a console command (for example {@code dprobe click ...}); it may itself be the call that parks
     */
    public void killAtGate(String gate, String trigger) throws IOException, InterruptedException {
        killAtGate(gate, trigger, () -> { });
    }

    /** A step the harness takes while the server is parked at a gate, before it kills it. */
    @FunctionalInterface
    public interface WhileParked {
        void run() throws IOException, InterruptedException;
    }

    /**
     * As {@link #killAtGate(String, String)}, with {@code whileParked} run between the marker and the kill. It is for the
     * gates that park a thread that is <em>not</em> the main one (the ledger writer's IO thread): the server still ticks,
     * so the step can be a {@code dprobe save} or a {@code dprobe leave}, which is how a snapshot that holds a frozen
     * entry gets onto the disk while the INTENT is not yet (or only just) written.
     */
    public void killAtGate(String gate, String trigger, WhileParked whileParked)
            throws IOException, InterruptedException {
        probe("arm", gate);
        int cursor = log.mark();
        console.send(trigger);
        if (log.awaitNewLine(GATE_MARKER + gate, options.commandTimeout(), cursor) == null) {
            throw new IllegalStateException("gate '" + gate + "' was not reached after '" + trigger + "'\n" + tail());
        }
        whileParked.run();
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
