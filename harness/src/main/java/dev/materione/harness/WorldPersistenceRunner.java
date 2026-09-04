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
 * The M2 exit gate (roadmap.md §M2): a fixture world with blocks of every
 * technique plus furniture survives <em>reload</em>, <em>restart</em> and
 * <em>world copy</em>. Five boots across four workspaces:
 *
 * <ol>
 *   <li>boot A — place one block per technique + the shaped pair + a full
 *       furniture cluster, verify, reload (the visuals-only policy must
 *       keep every placement), fault-inject broken rules mid-reload,
 *       stop;</li>
 *   <li>boot A again on the same workspace — restart survival;</li>
 *   <li>fresh workspace B carrying A's copied {@code world} folder —
 *       world-copy survival;</li>
 *   <li>two boots in a fourth workspace for the corruption pair: place a
 *       block, stop, delete the disposable allocation table and add a
 *       definition sorting ahead of it. The reboot rebuilds the accelerator
 *       and hands the slot to the other id — the chunk-PDC truth layer must
 *       flag the mismatch (MTR-0024) and drift-resync instead of guessing.</li>
 * </ol>
 */
public final class WorldPersistenceRunner {

    private LogWatcher lastLog;
    private LogWatcher lastStdout;

    /** Runs the scenario; every failure surfaces as a problem string. */
    public HarnessResult run(HarnessOptions options) {
        Set<String> packs = Set.of("sample", "corpus", "world");
        List<String> problems = new ArrayList<>();
        try {
            problems.addAll(runScenario(options, packs));
        } catch (IOException e) {
            problems.add("scenario I/O failure: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problems.add("interrupted");
        }
        var tail = new ArrayList<String>();
        if (lastStdout != null) {
            tail.addAll(lastStdout.tail(50));
        }
        if (lastLog != null) {
            tail.addAll(lastLog.tail(50));
        }
        return new HarnessResult(problems.isEmpty(), problems, problems.isEmpty() ? 0 : -1,
                options.workRoot(), tail);
    }

    private List<String> runScenario(HarnessOptions options, Set<String> packs)
            throws IOException, InterruptedException {

        var problems = new ArrayList<String>();

        // --- boot A: setup + reload survival ---------------------------------
        ServerWorkspace workspaceA = ServerWorkspace.create(options, "world-a", packs);
        Boot a1 = boot(options, workspaceA, problems, "MATERIONE-CONTENT-OK items=64");
        if (a1 != null) {
            settleChunk(a1, 0, 0);
            placeWorldSet(a1, options, problems);
            drive(a1, options, "materione dev sweep",
                    "MATERIONE-DEV-SWEEP indexed=6 ok=6 stale=0 orphans=0 drifted=0 unloaded=0",
                    problems);
            drive(a1, options, "materione dev inspect 0 200 0 world",
                    "MATERIONE-DEV-INSPECT found=true sample:amethyst_lamp", problems);
            drive(a1, options, "materione dev inspect 3 200 3 world",
                    "MATERIONE-DEV-INSPECT found=true world:ghost_panel", problems);
            drive(a1, options, "materione dev inspect 6 200 6 world",
                    "MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:workbench display=6.5,200.5,6.5",
                    problems);
            // the workbench declares no item: link — its auto placement item
            // must be live with a definition at the furniture's own id
            drive(a1, options, "materione dev stack world:workbench",
                    "MATERIONE-STACK id=world:workbench item_model=world:workbench", problems);
            // string technique: the host (tripwire) has no item form — the
            // auto placement item must carry a usable material (string)
            drive(a1, options, "materione dev stack world:string_fence",
                    "MATERIONE-STACK id=world:string_fence item_model=world:string_fence", problems);

            // reload must keep every placement (rebuild visuals only)
            drive(a1, options, "materione reload", "MATERIONE-RELOAD-OK items=64", problems);
            drive(a1, options, "materione dev sweep",
                    "MATERIONE-DEV-SWEEP indexed=6 ok=6 stale=0 orphans=0 drifted=0 unloaded=0",
                    problems);
            drive(a1, options, "materione dev inspect 6 200 6 world",
                    "MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:workbench display=6.5,200.5,6.5",
                    problems);
            invalidRuleFault(a1, options, workspaceA, problems);
            stop(a1, options, problems);
        }

        // --- boot A again: restart survival -----------------------------------
        Boot a2 = boot(options, workspaceA, problems, "MATERIONE-CONTENT-OK items=64");
        if (a2 != null) {
            settleChunk(a2, 0, 0);
            drive(a2, options, "materione dev sweep",
                    "MATERIONE-DEV-SWEEP indexed=6 ok=6 stale=0 orphans=0 drifted=0 unloaded=0",
                    problems);
            drive(a2, options, "materione dev inspect 0 200 0 world",
                    "MATERIONE-DEV-INSPECT found=true sample:amethyst_lamp", problems);
            drive(a2, options, "materione dev inspect 6 200 6 world",
                    "MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:workbench display=6.5,200.5,6.5",
                    problems);
            stop(a2, options, problems);
        }

        // --- boot B: world-copy survival ---------------------------------------
        ServerWorkspace workspaceB = ServerWorkspace.create(options, "world-b", packs);
        copyDirectory(workspaceA.root.resolve("world"), workspaceB.root.resolve("world"));
        Boot b1 = boot(options, workspaceB, problems, "MATERIONE-CONTENT-OK items=64");
        if (b1 != null) {
            settleChunk(b1, 0, 0);
            drive(b1, options, "materione dev sweep",
                    "MATERIONE-DEV-SWEEP indexed=6 ok=6 stale=0 orphans=0 drifted=0 unloaded=0",
                    problems);
            drive(b1, options, "materione dev inspect 0 200 0 world",
                    "MATERIONE-DEV-INSPECT found=true sample:amethyst_lamp", problems);
            drive(b1, options, "materione dev inspect 6 200 6 world",
                    "MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:workbench display=6.5,200.5,6.5",
                    problems);
            stop(b1, options, problems);
        }

        // --- corruption pair ----------------------------------------------------
        problems.addAll(corruptionPair(options, packs));

        return problems;
    }

    /** One block per state technique + shaped pair + the full furniture cluster. */
    private void placeWorldSet(Boot boot, HarnessOptions options, List<String> problems)
            throws IOException, InterruptedException {
        String[][] places = {
                {"sample:amethyst_lamp", "0"},   // note_block
                {"world:string_fence", "1"},     // string (tripwire)
                {"world:chorus_pillar", "2"},    // chorus
                {"world:ghost_panel", "3"},      // technical (bone_block host)
                {"sample:marble_stairs", "4"},   // shaped stairs
                {"sample:marble_slab", "5"},     // shaped slab
        };
        for (String[] place : places) {
            drive(boot, options,
                    "materione dev place " + place[0] + " " + place[1] + " 200 " + place[1] + " world",
                    "MATERIONE-DEV-PLACE ok=true", problems);
        }
        drive(boot, options, "materione dev place world:workbench 6 200 6 world",
                "MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:workbench", problems);
    }

    /**
     * Drops an item file whose two behavior rules break differently — one
     * condition that cannot parse (MTR-0022), one with no actions
     * (MTR-0021) — and proves the reload stays fail-soft: both codes land,
     * every other item stays live, and removing the file heals cleanly.
     */
    private void invalidRuleFault(
            Boot boot, HarnessOptions options, ServerWorkspace workspace, List<String> problems)
            throws IOException, InterruptedException {
        Path items = workspace.root
                .resolve("plugins").resolve("Materione").resolve("content").resolve("packs")
                .resolve("world").resolve("items");
        Path broken = items.resolve("broken_rule.yml");
        Files.writeString(broken, """
                broken_rule:
                  material: STICK
                  display_name: "Broken Rules"
                  behavior:
                    - on: click:right
                      if:
                        - "player.health >"
                      do:
                        - message: "never parsed"
                    - on: click:left
                      do: []
                """);
        int cursor = boot.log().mark();
        drive(boot, options, "materione reload", "MATERIONE-RELOAD-OK items=65", problems);
        if (boot.log().awaitLine("MTR-0022", java.time.Duration.ofSeconds(5), cursor) == null) {
            problems.add("fault injection: MTR-0022 missing for the unparseable condition");
        }
        if (boot.log().awaitLine("MTR-0021", java.time.Duration.ofSeconds(5), cursor) == null) {
            problems.add("fault injection: MTR-0021 missing for the empty rule chain");
        }
        Files.deleteIfExists(broken);
        drive(boot, options, "materione reload", "MATERIONE-RELOAD-OK items=64", problems);
    }

    /**
     * Places a block, stops, deletes the disposable allocation table and
     * adds a definition sorting ahead of it; the reboot rebuilds the table
     * and hands the slot to the other id. The chunk-PDC record wins:
     * inspect reports the mismatch (MTR-0024) and sweep drift-counts the
     * position with a targeted resync — nothing is guessed, nothing lost.
     * {@code sweep --fix} then rewrites the drifted host state from the
     * record, and the placement identifies and validates as its recorded
     * definition again.
     */
    private List<String> corruptionPair(HarnessOptions options, Set<String> packs)
            throws IOException, InterruptedException {
        var problems = new ArrayList<String>();
        ServerWorkspace workspace = ServerWorkspace.create(options, "world-fault", packs);

        Boot f1 = boot(options, workspace, problems, "MATERIONE-CONTENT-OK items=64");
        if (f1 == null) {
            return problems;
        }
        settleChunk(f1, 16, 16);
        drive(f1, options, "materione dev place sample:amethyst_lamp 20 200 20 world",
                "MATERIONE-DEV-PLACE ok=true", problems);
        drive(f1, options, "materione dev sweep",
                "MATERIONE-DEV-SWEEP indexed=1 ok=1 stale=0 orphans=0 drifted=0 unloaded=0",
                problems);
        stop(f1, options, problems);

        Path blocks = workspace.root
                .resolve("plugins").resolve("Materione").resolve("content").resolve("packs")
                .resolve("sample").resolve("blocks");
        Files.writeString(blocks.resolve("aaa_crate.yml"), """
                aaa_crate:
                  technique: note_block
                  texture: block/marble
                  display_name: "Crate"
                """);
        Files.deleteIfExists(workspace.root
                .resolve("plugins").resolve("Materione").resolve("storage").resolve("allocation.bin"));

        // aaa_crate (a block with no item: link) adds one auto placement item
        Boot f2 = boot(options, workspace, problems, "MATERIONE-CONTENT-OK items=65");
        if (f2 == null) {
            return problems;
        }
        settleChunk(f2, 16, 16);
        int cursor = f2.log().mark();
        drive(f2, options, "materione dev inspect 20 200 20 world",
                "MATERIONE-DEV-INSPECT found=false", problems);
        if (f2.log().awaitLine("MTR-0024", java.time.Duration.ofSeconds(5), cursor) == null) {
            problems.add("corruption pair: MTR-0024 missing after the accelerator rebuild");
        }
        drive(f2, options, "materione dev sweep",
                "MATERIONE-DEV-SWEEP indexed=1 ok=0 stale=0 orphans=0 drifted=1 unloaded=0",
                problems);
        // --fix rewrites the drifted host state from the chunk record
        drive(f2, options, "materione dev sweep --fix",
                "MATERIONE-DEV-SWEEP indexed=1 ok=0 stale=0 orphans=0 drifted=1 unloaded=0 fixed",
                problems);
        drive(f2, options, "materione dev inspect 20 200 20 world",
                "MATERIONE-DEV-INSPECT found=true sample:amethyst_lamp", problems);
        drive(f2, options, "materione dev sweep",
                "MATERIONE-DEV-SWEEP indexed=1 ok=1 stale=0 orphans=0 drifted=0 unloaded=0",
                problems);
        stop(f2, options, problems);
        return problems;
    }

    // --- boot plumbing ---------------------------------------------------------

    private record Boot(
            Process process, ConsoleDriver console, LogWatcher log, LogWatcher stdout,
            ServerWorkspace workspace) implements AutoCloseable {
        @Override
        public void close() {
            PaperHarnessRunner.killTree(process);
        }
    }

    /** Starts the server and waits for boot plus the expected content marker. */
    private Boot boot(HarnessOptions options, ServerWorkspace workspace, List<String> problems,
            String contentMarker) throws IOException, InterruptedException {
        LogWatcher log = new LogWatcher(workspace.logFile);
        LogWatcher stdout = new LogWatcher(workspace.stdoutFile);
        lastLog = log;
        lastStdout = stdout;
        // a reused workspace may still hold the previous boot's latest.log
        // (Paper rotates it lazily): this boot's markers only count in content
        // written after the snapshot — appended past it, or in a fresh file
        String previous = log.readAll();
        Process process = new ProcessBuilder(
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
        String done = awaitBootDone(log, previous, options.bootTimeout());
        String name = workspace.root.getFileName().toString();
        if (done == null) {
            problems.add(name + ": boot deadline exceeded (" + options.bootTimeout().toSeconds() + "s)");
            PaperHarnessRunner.killTree(process);
            return null;
        }
        String content = log.readAll();
        if (!newContentContains(content, previous, "MATERIONE-BOOT-OK")) {
            problems.add(name + ": MATERIONE-BOOT-OK missing after boot");
        }
        if (contentMarker != null && !newContentContains(content, previous, contentMarker)) {
            problems.add(name + ": '" + contentMarker + "' missing after boot");
        }
        return new Boot(process, new ConsoleDriver(process.getOutputStream()), log, stdout, workspace);
    }

    /**
     * Waits for THIS boot's {@code Done (} line: content appended past the
     * pre-boot snapshot, or any position in a rotation-replaced file.
     */
    private String awaitBootDone(LogWatcher log, String previous, java.time.Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String content = log.readAll();
            if (newContentContains(content, previous, "Done (")) {
                for (String line : content.split("\n", -1)) {
                    if (line.contains("Done (")) {
                        return line;
                    }
                }
            }
            Thread.sleep(200);
        }
        return null;
    }

    /**
     * True when {@code needle} appears in content written by this boot:
     * skip a prefix identical to the previous boot's log (append case);
     * once the file was replaced, any position is this boot's.
     */
    private static boolean newContentContains(String content, String previous, String needle) {
        if (content == null) {
            return false;
        }
        int from = 0;
        if (previous != null && !previous.isEmpty() && content.startsWith(previous)) {
            from = previous.length();
            if (from > content.length()) {
                return false; // torn rotation read
            }
        }
        return content.indexOf(needle, Math.min(from, content.length())) >= 0;
    }

    private void stop(Boot boot, HarnessOptions options, List<String> problems)
            throws IOException, InterruptedException {
        String name = boot.workspace().root.getFileName().toString();
        boot.console().send("stop");
        if (boot.log().awaitLine("MATERIONE-BYE", options.stopTimeout()) == null) {
            problems.add(name + ": MATERIONE-BYE missing after stop");
        }
        if (!boot.process().waitFor(options.stopTimeout().toSeconds(), TimeUnit.SECONDS)) {
            problems.add(name + ": server did not exit within "
                    + options.stopTimeout().toSeconds() + "s of stop");
        }
        boot.close();
    }

    private void drive(Boot boot, HarnessOptions options, String command, String marker,
            List<String> problems) throws IOException, InterruptedException {
        String name = boot.workspace().root.getFileName().toString();
        int cursor = boot.log().mark();
        boot.console().send(command);
        String line = boot.log().awaitLine(marker, options.commandTimeout(), cursor);
        if (line == null) {
            String content = boot.log().readAll();
            boolean presentAnywhere = content != null && content.contains(marker);
            problems.add(name + ": marker '" + marker + "' missing after '" + command
                    + "' (marker elsewhere in log: " + presentAnywhere
                    + ", cursor=" + cursor + ", logLen="
                    + (content == null ? -1 : content.length()) + ")");
        }
    }

    /**
     * Forces the chunk containing block (x,z), waits for the server's
     * forceload response, then lets the chunk system settle — position reads
     * racing an in-flight chunk load see half-applied state (block data from
     * disk, PDC not yet hydrated).
     */
    private void settleChunk(Boot boot, int x, int z) throws IOException, InterruptedException {
        int cursor = boot.log().mark();
        boot.console().send("forceload add " + x + " " + z);
        // matches both responses: "Marked chunk [1, 1] …" and
        // "No chunks were marked for force loading" — the cursor keeps us
        // off Paper's own identically-worded startup line
        boot.log().awaitLine("arked", java.time.Duration.ofSeconds(15), cursor);
        Thread.sleep(2000);
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path file : walk.toList()) {
                Path destination = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
