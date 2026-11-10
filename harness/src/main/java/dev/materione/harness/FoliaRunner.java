package dev.materione.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Probe P1 of gui-input-durability.md section 11 (row R2, #306), the experiment half: on a Folia server, can the player's
 * save interleave with a plugin step running on the player's entity scheduler (F12)?
 *
 * <p>The design answer comes from reading Folia's bytecode (recorded in section 3): in one region tick the server drains
 * the region's tasks, runs the entity schedulers of the region's entities, ticks the region's connections, and only then
 * calls {@code PlayerList.saveAll(rate)}, which saves a player only when the current thread is that player's tick thread.
 * This scenario checks it by observation on the real server, with a positive control:
 * <ol>
 *   <li>a real protocol client joins (Folia's join is a region-scheduled sequence a fake connection cannot reproduce);</li>
 *   <li>a probe entity-scheduler task, every tick, writes persistent data {@code a=n}, holds the region thread 20 ms, then
 *       writes {@code b=n}; the Paper autosave runs at its fastest rate (every tick) and the harness reads the player's
 *       {@code .dat} in a tight loop. A save that interleaved with the step would capture {@code a != b};</li>
 *   <li>the control: a plain thread calls {@code Player#saveData} in a loop while the step runs. If the detector can see
 *       interleaving, this run shows {@code a != b} (or Folia rejects the off-thread save, which is its own answer).</li>
 * </ol>
 * The verdict is the pair (no mismatch under the autosave alone) and (the control is visible). Opt-in:
 * {@code -Pharness.folia=true}, after {@code npm ci --ignore-scripts} in {@code harness/protocol}.
 */
public final class FoliaRunner {

    static final String PLAYER = "FoliaBot";

    /** The fastest autosave Paper offers: a player is eligible every tick. */
    static final String AUTOSAVE_EVERY_TICK = """
            player-auto-save:
              rate: 1
              max-per-tick: 10
            """;

    private static final String FPROBE = "MATERIONE-FPROBE ";

    /** What the reader of the .dat saw: snapshots read, distinct steps seen, and how many had a different from b. */
    record Sampling(int reads, int distinct, int mismatches, String example) {}

    public HarnessResult run(HarnessOptions options) {
        List<String> problems = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        CrashRig rig = null;
        Process bot = null;
        try {
            Path foliaJar = Path.of(System.getProperty("harness.folia.jar", ""));
            Path protocol = Path.of(System.getProperty("harness.protocolDir", ""));
            if (!Files.isRegularFile(foliaJar)) {
                throw new IOException("harness.folia.jar is not set or missing: run via :harness:integrationTest -Pharness.folia=true");
            }
            if (!Files.isDirectory(protocol.resolve("node_modules").resolve("minecraft-protocol"))) {
                throw new IOException("protocol dependency missing: run npm ci --ignore-scripts in harness/protocol");
            }
            CrashRig started = CrashRig.create(options, foliaJar, "folia-probe", AUTOSAVE_EVERY_TICK,
                    "harness.foliaProbeJar", FPROBE + "ready", false);
            rig = started;
            started.boot();
            evidence.add("server jar: " + foliaJar.getFileName() + " (sha256 pinned in gradle/libs.versions.toml)");

            int port = Integer.getInteger("harness.serverPort", 25599);
            Path botLog = started.workspace().root.resolve("idle-bot.log");
            bot = new ProcessBuilder("node", protocol.resolve("idle-bot.mjs").toString(), Integer.toString(port), PLAYER)
                    .directory(protocol.toFile()).redirectErrorStream(true).redirectOutput(botLog.toFile()).start();
            if (started.log().awaitLine(PLAYER + " joined the game", options.commandTimeout()) == null) {
                throw new IllegalStateException("the protocol client did not join\n" + String.join("\n", started.tailLines()));
            }

            // 1. The thread facts
            String thread = started.command("fprobe thread " + PLAYER, FPROBE + "thread ");
            evidence.add(thread.substring(thread.indexOf(FPROBE)));
            if (!thread.contains("ok=true") || !thread.contains("owned=true")) {
                problems.add("the entity-scheduler task did not run on the player's owning region: " + thread);
            }

            // 2. The autosave alone, against a step that holds the thread for 20 ms between its two writes
            started.command("fprobe straddle " + PLAYER, FPROBE + "straddle ");
            Sampling alone = sample(started, Duration.ofSeconds(15));
            evidence.add("autosave alone: " + alone);
            if (alone.distinct() < 20) {
                problems.add("inconclusive: only " + alone.distinct() + " distinct snapshots were read (the autosave is not running)");
            }
            if (alone.mismatches() != 0) {
                problems.add("F12 REFUTED: a snapshot caught the step half-done: " + alone);
            }

            // 3. The positive control: an off-thread saveData loop, against the same step
            int cursor = started.log().mark();
            started.send("fprobe control " + PLAYER + " 400");
            Sampling control = sample(started, Duration.ofSeconds(15));
            String controlLine = started.log().awaitNewLine(FPROBE + "control ok=", options.commandTimeout(), cursor);
            evidence.add("off-thread save control: " + control + " | " + (controlLine == null ? "no answer"
                    : controlLine.substring(controlLine.indexOf(FPROBE))));
            boolean offThreadRejected = controlLine != null && !controlLine.contains("firstFailure=none");
            if (control.mismatches() == 0 && !offThreadRejected) {
                problems.add("the control is blind: an off-thread save loop produced no mismatch and no failure, so the "
                        + "detector proves nothing: " + control + " | " + controlLine);
            }

            started.command("fprobe stop", FPROBE + "stop ");

            // 4. The shutdown path: Folia's stopServer calls removeAll only (no saveAll before it); the file is complete
            if (bot != null) {
                bot.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
                bot.getOutputStream().flush();
            }
            int exit = started.stop();
            if (exit != 0) {
                problems.add("non-zero exit " + exit);
            }
            PlayerDat dat = started.savedData(PLAYER);
            Object av = value(dat, ":a");
            Object bv = value(dat, ":b");
            evidence.add("after stop: a=" + av + " b=" + bv);
            if (av == null || !av.equals(bv)) {
                problems.add("the file the stop wrote has a half-done step: a=" + av + " b=" + bv);
            }
        } catch (Exception e) {
            problems.add("scenario error: " + e);
        } finally {
            if (bot != null) {
                bot.destroyForcibly();
                try {
                    bot.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (rig != null) {
                rig.close();
                try {
                    Files.write(rig.workspace().root.resolve("folia-evidence.txt"), evidence, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // the evidence file is a convenience; the problems list is the verdict
                }
            }
        }
        return new HarnessResult(problems.isEmpty(), problems, -1, rig == null ? null : rig.workspace().root,
                rig == null ? List.of() : rig.tailLines());
    }

    /** Reads the player's .dat in a tight loop for {@code duration}; a file that is mid-replace is simply skipped. */
    private static Sampling sample(CrashRig rig, Duration duration) throws InterruptedException {
        long deadline = System.nanoTime() + duration.toNanos();
        int reads = 0;
        int mismatches = 0;
        java.util.Set<Object> seen = new java.util.HashSet<>();
        String example = "none";
        while (System.nanoTime() < deadline) {
            try {
                PlayerDat dat = rig.savedData(PLAYER);
                Object a = value(dat, ":a");
                Object b = value(dat, ":b");
                reads++;
                if (a != null) {
                    seen.add(a);
                    if (!a.equals(b)) {
                        mismatches++;
                        example = "a=" + a + " b=" + b;
                    }
                }
            } catch (IOException | RuntimeException notNow) {
                // being replaced, or not written yet
            }
            Thread.sleep(2);
        }
        return new Sampling(reads, seen.size(), mismatches, example);
    }

    private static Object value(PlayerDat dat, String suffix) {
        Map<String, Object> values = dat.bukkitValues();
        return values.entrySet().stream().filter(entry -> entry.getKey().endsWith(suffix)).map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }
}
