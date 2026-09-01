package dev.materione.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

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
            // sample+corpus only: the bootstrap gate pins 53 live items; the
            // world pack belongs to the world-persistence scenario
            workspace = ServerWorkspace.create(options, "bootstrap", Set.of("sample", "corpus"));
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
            if (log.readAll() == null || !log.readAll().contains("MATERIONE-CONTENT-OK items=55")) {
                problems.add("MATERIONE-CONTENT-OK items=55 missing after boot "
                        + "(fixture packs: 3 sample + 50 corpus items + 2 auto placement "
                        + "items (marble_stairs/slab) should be live)");
            }
            if (log.readAll() == null || !log.readAll().contains("MATERIONE-DELIVERY-OK")) {
                problems.add("MATERIONE-DELIVERY-OK missing after boot (self-host pack server)");
            }
            // pack-format detection reads the server jar's version.json — the
            // pinned 1.21.4 server reports 46, the advertised range stays 46..88
            if (log.readAll() == null
                    || !log.readAll().contains("MATERIONE-PACKFORMAT detected=46 advertising=46..88")) {
                problems.add("MATERIONE-PACKFORMAT detected=46 advertising=46..88 missing after boot "
                        + "(server-reported pack format detection)");
            }
            // pack.mcmeta schema: 1.21.9+/26.x clients read min_format/max_format
            // and ignore supported_formats — both generations must be staged
            try (ZipFile staged = new ZipFile(
                    workspace.root.resolve("plugins/Materione/generated/pack/pack.zip").toFile())) {
                var entry = staged.getEntry("pack.mcmeta");
                String mcmeta = entry == null ? ""
                        : new String(staged.getInputStream(entry).readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                if (!mcmeta.contains("\"min_format\"") || !mcmeta.contains("\"max_format\"")) {
                    problems.add("staged pack.mcmeta missing min_format/max_format: " + mcmeta
                            + " (26.x clients reject packs without them)");
                }
                // item model definition wire format: only "minecraft:model" +
                // "model" parses on 1.21.4+ clients (a real client caught the
                // invalid "reference"/"id" shape the harness never sees)
                var definition = staged.getEntry("assets/corpus/items/gem_red.json");
                String definitionJson = definition == null ? ""
                        : new String(staged.getInputStream(definition).readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                if (!definitionJson.contains("\"type\":\"minecraft:model\"")
                        || !definitionJson.contains("\"model\":\"corpus:gem_red_model\"")) {
                    problems.add("staged item definition has the wrong wire format: " + definitionJson
                            + " (clients need {\"model\":{\"type\":\"minecraft:model\",\"model\":…}})");
                }
                // block models: the parent's texture variable must carry the
                // texture — cube_all reads "all"; the item idiom "layer0"
                // resolves on nothing and every face renders missing-texture
                var blockModel = staged.getEntry("assets/sample/models/amethyst_lamp_model.json");
                String blockModelJson = blockModel == null ? ""
                        : new String(staged.getInputStream(blockModel).readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                if (!blockModelJson.contains("\"all\":\"sample:block/amethyst_lamp\"")) {
                    problems.add("staged block model keys the texture wrong: " + blockModelJson
                            + " (block/cube_all reads \"all\", not \"layer0\")");
                }
                // furniture displays: the ItemDisplay carries item_model — a
                // definition must exist at the model ref or the client renders
                // the missing model
                var furnitureDefinition = staged.getEntry("assets/sample/items/chair_model.json");
                String furnitureJson = furnitureDefinition == null ? ""
                        : new String(staged.getInputStream(furnitureDefinition).readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                if (!furnitureJson.contains("\"type\":\"minecraft:model\"")
                        || !furnitureJson.contains("\"model\":\"sample:chair_model\"")) {
                    problems.add("staged furniture display definition missing/wrong: " + furnitureJson
                            + " (item_model needs a definition at assets/<ns>/items/<model>.json)");
                }
            } catch (IOException e) {
                problems.add("could not read staged pack.zip mcmeta: " + e);
            }

            problems.addAll(drive(console, log, options, "materione version",
                    "MATERIONE-VERSION " + options.expectedVersion()));
            problems.addAll(drive(console, log, options, "materione doctor",
                    "MATERIONE-DOCTOR-OK"));
            // give/browser wiring (console has no players, so the denial and
            // unknown-target paths are exactly what proves the command surface)
            problems.addAll(drive(console, log, options,
                    "materione give corpus:gem_red",
                    "MATERIONE-GIVE-DENIED reason=player-required"));
            problems.addAll(drive(console, log, options,
                    "materione give corpus:gem_red ghostplayer",
                    "MATERIONE-GIVE-FAILED reason=no-such-player"));
            problems.addAll(drive(console, log, options, "materione browser",
                    "MATERIONE-BROWSER-DENIED reason=player-only"));
            // the item_model component must name the ITEM DEFINITION id (what
            // the compiler writes to assets/<ns>/items/<id>.json), not the pack
            // model — a real client checkerboarded every modeled item when the
            // runtime pointed it at <id>_model, where no definition exists
            problems.addAll(drive(console, log, options,
                    "materione dev stack corpus:gem_red",
                    "MATERIONE-STACK id=corpus:gem_red item_model=corpus:gem_red"));
            // material-only items carry no component at all (vanilla render)
            problems.addAll(drive(console, log, options,
                    "materione dev stack corpus:scroll_01",
                    "MATERIONE-STACK id=corpus:scroll_01 item_model=none"));
            // auto placement items: marble_stairs has no item: link, so the
            // runtime synthesizes a grabbable item at the block's own id
            problems.addAll(drive(console, log, options,
                    "materione dev stack sample:marble_stairs",
                    "MATERIONE-STACK id=sample:marble_stairs item_model=sample:marble_stairs"));
            // M2 slice 3: world-runtime smoke — force-load the target chunk
            // (headless boots have no players, so nothing else keeps it warm),
            // then place → inspect → remove → sweep through the dev driver
            console.send("forceload add 0 0");
            Thread.sleep(500);
            problems.addAll(drive(console, log, options,
                    "materione dev place sample:amethyst_lamp 0 200 0 world",
                    "MATERIONE-DEV-PLACE ok=true"));
            problems.addAll(drive(console, log, options,
                    "materione dev inspect 0 200 0 world",
                    "MATERIONE-DEV-INSPECT found=true sample:amethyst_lamp"));
            problems.addAll(drive(console, log, options,
                    "materione dev sweep",
                    "MATERIONE-DEV-SWEEP indexed=1 ok=1 stale=0 orphans=0 drifted=0 unloaded=0"));
            problems.addAll(drive(console, log, options,
                    "materione dev remove 0 200 0 world",
                    "MATERIONE-DEV-REMOVE ok=true"));
            // shaped techniques (M2 slice 4): PDC-identified stairs/slabs
            problems.addAll(drive(console, log, options,
                    "materione dev place sample:marble_stairs 1 200 1 world",
                    "MATERIONE-DEV-PLACE ok=true"));
            problems.addAll(drive(console, log, options,
                    "materione dev place sample:marble_slab 2 200 2 world",
                    "MATERIONE-DEV-PLACE ok=true"));
            problems.addAll(drive(console, log, options,
                    "materione dev sweep",
                    "MATERIONE-DEV-SWEEP indexed=2 ok=2 stale=0 orphans=0 drifted=0 unloaded=0"));
            problems.addAll(drive(console, log, options,
                    "materione dev inspect 1 200 1 world",
                    "MATERIONE-DEV-INSPECT found=true sample:marble_stairs"));
            problems.addAll(drive(console, log, options,
                    "materione dev remove 1 200 1 world",
                    "MATERIONE-DEV-REMOVE ok=true"));
            problems.addAll(drive(console, log, options,
                    "materione dev remove 2 200 2 world",
                    "MATERIONE-DEV-REMOVE ok=true"));
            problems.addAll(drive(console, log, options,
                    "materione dev sweep",
                    "MATERIONE-DEV-SWEEP indexed=0 ok=0"));
            // furniture core (M2 slice 5): cluster place + remove via the anchor cell;
            // inspect pins the root at the block center — the model must fill
            // the anchor cell, not sink half a block below it
            problems.addAll(drive(console, log, options,
                    "materione dev place sample:chair 3 200 3 world",
                    "MATERIONE-DEV-FURNITURE-PLACE ok=true placed sample:chair"));
            problems.addAll(drive(console, log, options,
                    "materione dev inspect 3 200 3 world",
                    "MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=sample:chair "
                            + "display=3.5,200.5,3.5"));
            problems.addAll(drive(console, log, options,
                    "materione dev remove 3 200 3 world",
                    "MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture"));
            console.send("forceload remove all");
            // no-op reload first: nothing changed since boot
            problems.addAll(drive(console, log, options, "materione reload",
                    "MATERIONE-RELOAD-OK items=55"));
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
                "MATERIONE-RELOAD-OK items=56"));

        Thread.sleep(1000);
        Files.writeString(broken, Files.readString(faultSource));
        int beforeFaultReload = log.mark();
        problems.addAll(drive(console, log, options, "materione reload",
                "MATERIONE-RELOAD-OK items=56"));
        if (log.awaitLine("MTR-0007", java.time.Duration.ofSeconds(5), beforeFaultReload) == null) {
            problems.add("fault injection: MTR-0007 missing after reloading a broken item file");
        }

        Thread.sleep(1000);
        Files.deleteIfExists(extra);
        Files.deleteIfExists(broken);
        problems.addAll(drive(console, log, options, "materione reload",
                "MATERIONE-RELOAD-OK items=55"));
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

    static void killTree(Process process) {
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
