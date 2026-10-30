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
            // sample+corpus+nested: the bootstrap gate pins 58 live items (the nested
            // pack's item is one of them, #191); the world pack belongs to the
            // world-persistence scenario
            workspace = ServerWorkspace.create(options, "bootstrap", Set.of("sample", "corpus", "nested"));
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
            if (log.readAll() == null || !log.readAll().contains("MATERIONE-CONTENT-OK items=58")) {
                problems.add("MATERIONE-CONTENT-OK items=58 missing after boot "
                        + "(fixture packs: 4 sample items (incl. the music disc) + 50 corpus "
                        + "items + 2 auto placement items (marble_stairs/slab) + the nested "
                        + "pack's item (#191) should be live)");
            }
            if (log.readAll() == null || !log.readAll().contains("MATERIONE-DELIVERY-OK")) {
                problems.add("MATERIONE-DELIVERY-OK missing after boot (self-host pack server)");
            }
            // logic providers (logic.md §4.5, §16, #213): this workspace has no Vault and no transfer host, so
            // the default currency materione:money (Vault) is unavailable, and the money and connect actions
            // report unavailable at boot, through the same availability the editor reads. The permission
            // actions stay available on the session default.
            if (log.readAll() == null || !log.readAll().contains(
                    "MATERIONE-LOGIC-OK currency=materione:money available=false transfer=none "
                            + "unavailable=give_money,take_money,connect")) {
                problems.add("MATERIONE-LOGIC-OK currency=materione:money available=false transfer=none "
                        + "unavailable=give_money,take_money,connect missing after boot (no Vault and no transfer "
                        + "host: materione:money must be unavailable and the money and connect actions must report it)");
            }
            // pack-format detection reads the server jar's version.json — the
            // pinned 1.21.4 server reports 46, the advertised range stays 46..88
            if (log.readAll() == null
                    || !log.readAll().contains("MATERIONE-PACKFORMAT detected=46 advertising=46..88")) {
                problems.add("MATERIONE-PACKFORMAT detected=46 advertising=46..88 missing after boot "
                        + "(server-reported pack format detection)");
            }
            // registry seam decision: the pinned 1.21.4 server has no compose
            // lifecycle and no JUKEBOX_SONG registry event, so the bootstrap
            // must report the datapack mode — paintings/songs ride the world
            // datapack here, exactly as on every pre-1.21.6 server
            if (log.readAll() == null || !log.readAll().contains("MATERIONE-REGISTRY-OK")
                    || !log.readAll().contains("mode=datapack")) {
                problems.add("MATERIONE-REGISTRY-OK mode=datapack missing after boot "
                        + "(bootstrap registry-seam capability detection)");
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
                // M4 media: the pack glyph font carries the bitmap providers
                // and references the shared shift font; minecraft:default must
                // reference both, or unstyled text never renders the glyphs
                var fontEntry = staged.getEntry("assets/sample/font/glyphs.json");
                String fontJson = fontEntry == null
                        ? ""
                        : new String(staged.getInputStream(fontEntry).readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                if (!fontJson.contains("\"type\":\"bitmap\"")
                        || !fontJson.contains("{\"id\":\"materione:shift\",\"type\":\"reference\"}")) {
                    problems.add("staged glyph font missing bitmap providers or the shift reference: "
                            + fontJson);
                }
                var defaultFontEntry = staged.getEntry("assets/minecraft/font/default.json");
                String defaultFontJson = defaultFontEntry == null
                        ? ""
                        : new String(staged.getInputStream(defaultFontEntry).readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                if (!defaultFontJson.contains("{\"id\":\"sample:glyphs\",\"type\":\"reference\"}")) {
                    problems.add("minecraft:font/default.json does not reference the pack glyph font: "
                            + defaultFontJson + " (unstyled text would render tofu boxes)");
                }
                // every font/atlas texture reference must resolve inside THIS
                // zip: a dangling bitmap "file" (or atlas "resource") renders
                // the missing-glyph box on clients — no error anywhere
                for (var entries = staged.entries(); entries.hasMoreElements(); ) {
                    var candidate = entries.nextElement();
                    String name = candidate.getName();
                    if (!name.startsWith("assets/") || !name.endsWith(".json")
                            || !(name.contains("/font/") || name.contains("/atlases/"))) {
                        continue;
                    }
                    String json = new String(staged.getInputStream(candidate).readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    var refs = java.util.regex.Pattern
                            .compile("\"(?:file|resource)\":\"([A-Za-z0-9_.-]+):([A-Za-z0-9_./-]+)\"")
                            .matcher(json);
                    while (refs.find()) {
                        // font bitmap "file" carries its .png (1.21.2+ format);
                        // atlas "resource" sprite ids do not
                        String path = refs.group(2).endsWith(".png")
                                ? refs.group(2).substring(0, refs.group(2).length() - 4)
                                : refs.group(2);
                        String texturePath = "assets/" + refs.group(1) + "/textures/" + path + ".png";
                        if (staged.getEntry(texturePath) == null) {
                            problems.add("staged " + name + " references missing texture "
                                    + texturePath + " (renders as tofu boxes on clients)");
                        }
                    }
                }
                var soundsEntry = staged.getEntry("assets/sample/sounds.json");
                String soundsJson = soundsEntry == null ? ""
                        : new String(staged.getInputStream(soundsEntry).readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                if (!soundsJson.contains("\"anthem\":") || !soundsJson.contains("\"level_up\":")) {
                    problems.add("staged sounds.json missing custom events: " + soundsJson);
                }
                // no generated painting atlas: vanilla's paintings atlas
                // (atlases/paintings.json) directory-scans textures/painting/
                // across all namespaces with an empty prefix, so the sprite id
                // is <ns>:<path> and any generated atlas file is an orphan
                // the client never loads
                if (staged.getEntry("assets/minecraft/atlases/painting.json") != null
                        || staged.getEntry("assets/minecraft/atlases/paintings.json") != null) {
                    problems.add("staged pack ships a generated painting atlas — vanilla's"
                            + " own paintings atlas already registers textures/painting/"
                            + " sprites for every namespace");
                }
                // text-effect shader ladder: the sample pack defines rainbow +
                // wave, so every verified rung must ride as an overlay — never
                // the base layer — and the 1.21.4 rung must carry the include
                // with both effects' signal branches
                if (staged.getEntry(
                        "materione_1_21_4/assets/minecraft/shaders/core/rendertype_text.vsh") == null
                        || staged.getEntry(
                                "materione_1_21_4/assets/minecraft/shaders/include/materione_text_effects.glsl")
                                == null) {
                    problems.add("staged pack missing the 1.21.4 shader overlay — text effects"
                            + " (effects/*.yml) compile to a version-laddered rendertype_text"
                            + " replacement");
                }
                // the world datapack (paintings + jukebox songs) deploys to the
                // default world's datapacks folder at compile time — and it
                // must actually carry the painting variant, because that is
                // the only thing that makes a custom painting placeable
                Path deployed = workspace.root.resolve("world/datapacks/materione-content.zip");
                if (!Files.isRegularFile(deployed)) {
                    problems.add("world datapack not deployed to " + deployed
                            + " (painting/jukebox artifacts ride the world datapack)");
                } else {
                    try (ZipFile datapack = new ZipFile(deployed.toFile())) {
                        if (datapack.getEntry("data/sample/painting_variant/sunrise.json") == null) {
                            problems.add("deployed world datapack is missing the sunrise"
                                    + " painting variant — custom paintings only exist after"
                                    + " their datapack loads");
                        }
                        var tag = datapack.getEntry(
                                "data/minecraft/tags/painting_variant/placeable.json");
                        String tagJson = tag == null ? ""
                                : new String(datapack.getInputStream(tag).readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8);
                        if (!tagJson.contains("sample:sunrise")) {
                            problems.add("deployed world datapack's placeable tag does not"
                                    + " name sample:sunrise: " + tagJson);
                        }
                        // the variant's asset_id must follow vanilla's sprite
                        // convention: <ns>:<path> with no painting/ segment,
                        // because vanilla's paintings atlas registers the
                        // texture under the directory-stripped id — a prefix
                        // in the asset_id points at a sprite that never exists
                        // (invisible checkerboard on every wall)
                        var variant = datapack.getEntry("data/sample/painting_variant/sunrise.json");
                        String variantJson = variant == null ? ""
                                : new String(datapack.getInputStream(variant).readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8);
                        int assetIdStart = variantJson.indexOf("\"asset_id\":\"");
                        String assetId = assetIdStart < 0 ? ""
                                : variantJson.substring(assetIdStart + "\"asset_id\":\"".length(),
                                        variantJson.indexOf('"', assetIdStart + "\"asset_id\":\"".length()));
                        if (!assetId.equals("sample:sunrise")) {
                            problems.add("painting variant asset_id '" + assetId
                                    + "' must be sample:sunrise (vanilla atlas sprite convention)");
                        }
                    } catch (IOException e) {
                        problems.add("could not read the deployed world datapack: " + e);
                    }
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
            // M4 media surfaces: the music disc resolves (jukebox component is
            // silent until the datapack loads at the next restart), and the
            // dev sound driver answers a console sender with its denial marker
            problems.addAll(drive(console, log, options,
                    "materione dev stack sample:ruby_disc",
                    "MATERIONE-STACK id=sample:ruby_disc item_model=none"));
            problems.addAll(drive(console, log, options,
                    "materione dev sound sample:level_up",
                    "MATERIONE-DEV-SOUND-DENIED reason=player-required"));
            problems.addAll(drive(console, log, options,
                    "materione dev painting sample:sunrise",
                    "MATERIONE-DEV-PAINTING-DENIED reason=player-required"));
            // the world datapack is self-healing: with the pack bytes unchanged
            // (a noop reload), deleting the deployed zip still gets repaired —
            // a regenerated world used to leave the variant missing forever
            java.nio.file.Files.deleteIfExists(
                    workspace.root.resolve("world/datapacks/materione-content.zip"));
            problems.addAll(drive(console, log, options, "materione reload",
                    "MATERIONE-RELOAD-OK"));
            for (int wait = 0; wait < 20 && !java.nio.file.Files.isRegularFile(
                    workspace.root.resolve("world/datapacks/materione-content.zip")); wait++) {
                Thread.sleep(250);
            }
            if (!java.nio.file.Files.isRegularFile(
                    workspace.root.resolve("world/datapacks/materione-content.zip"))) {
                problems.add("world datapack was not repaired after deletion + reload"
                        + " (deploy skips must verify the world folder, not a marker)");
            }
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
            // no-op reload first: nothing changed since boot (58 includes the nested fixture item, #191)
            problems.addAll(drive(console, log, options, "materione reload",
                    "MATERIONE-RELOAD-OK items=58"));
            hotReloadScenario(console, log, workspace, options, problems);
            nestedHotReloadScenario(console, log, workspace, options, problems);

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
                "MATERIONE-RELOAD-OK items=59"));

        Thread.sleep(1000);
        Files.writeString(broken, Files.readString(faultSource));
        int beforeFaultReload = log.mark();
        problems.addAll(drive(console, log, options, "materione reload",
                "MATERIONE-RELOAD-OK items=59"));
        if (log.awaitLine("MTR-0007", java.time.Duration.ofSeconds(5), beforeFaultReload) == null) {
            problems.add("fault injection: MTR-0007 missing after reloading a broken item file");
        }

        Thread.sleep(1000);
        Files.deleteIfExists(extra);
        Files.deleteIfExists(broken);
        problems.addAll(drive(console, log, options, "materione reload",
                "MATERIONE-RELOAD-OK items=58"));
    }

    /**
     * The nested-discovery scenario (issue #191), live on Paper: an item in a
     * subfolder of a content directory goes live on reload, a broken file in that
     * subfolder is quarantined with MTR-0007 while everything else stays live, and
     * removing both returns to the boot state. The same sequence as the flat
     * scenario above, one folder deeper.
     */
    private void nestedHotReloadScenario(ConsoleDriver console, LogWatcher log, ServerWorkspace workspace,
            HarnessOptions options, List<String> problems) throws IOException, InterruptedException {
        Path nestedItems = workspace.root
                .resolve("plugins").resolve("Materione")
                .resolve("content").resolve("packs").resolve("nested")
                .resolve("items").resolve("weapons");
        Path faultSource = options.contentPacksDir() == null
                ? null
                : options.contentPacksDir().getParent().resolve("faults").resolve("hot-reload-broken.yml");
        if (faultSource == null || !Files.isDirectory(nestedItems) || !Files.isRegularFile(faultSource)) {
            problems.add("nested hot-reload scenario skipped: nested pack or fault fixture missing");
            return;
        }

        Path extra = nestedItems.resolve("nested_gem.yml");
        Path broken = nestedItems.resolve("nested_gem_broken.yml");

        Files.writeString(extra, """
                nested_gem:
                  material: DIAMOND
                  display_name: "Nested Gem"
                """);
        problems.addAll(drive(console, log, options, "materione reload",
                "MATERIONE-RELOAD-OK items=59"));

        Thread.sleep(1000);
        Files.writeString(broken, Files.readString(faultSource));
        int beforeFaultReload = log.mark();
        problems.addAll(drive(console, log, options, "materione reload",
                "MATERIONE-RELOAD-OK items=59"));
        if (log.awaitLine("MTR-0007", java.time.Duration.ofSeconds(5), beforeFaultReload) == null) {
            problems.add("fault injection: MTR-0007 missing after reloading a broken nested item file");
        }

        Thread.sleep(1000);
        Files.deleteIfExists(extra);
        Files.deleteIfExists(broken);
        problems.addAll(drive(console, log, options, "materione reload",
                "MATERIONE-RELOAD-OK items=58"));
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
