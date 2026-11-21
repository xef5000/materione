package dev.materione.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The entity and world verbs on a real Paper server (logic.md §41, #329): a pickaxe whose {@code break_blocks} mines a 3 by 3
 * area through the player's own break path, where a stand-in protection plugin (the probe's {@code dprobe protect}, a
 * {@code BlockBreakEvent} canceller) keeps one block, and a dash ({@code velocity}) that moves the player.
 *
 * <p>The players are the fake-connection players of {@link CrashRig}; the rules are the {@code worldverbs} fixture pack, each
 * announcing itself with a {@code say MATERIONE-WORLDVERBS-...} line. The world is read back from the server (the probe prints a
 * block's material and a player's velocity), not from a marker alone. This proves the logic on Paper, where every thread check
 * is true; the cross-region hop of the same code is proved at the {@code Regions} seam by unit tests, and nothing here ran on
 * Folia. Particles are packets nobody can read back headlessly: they are the QA catalog's {@code worldverbs} suite.
 */
public final class WorldVerbsRunner {

    private static final String M = "MATERIONE-WORLDVERBS-";
    private static final String P = CrashRig.MARKER;

    public HarnessResult run(HarnessOptions options) {
        List<String> problems = new ArrayList<>();
        CrashRig rig = null;
        try {
            rig = CrashRig.createForEscrow(options, "world-verbs", null, Set.of("worldverbs"));
            rig.boot();
            scenario(rig, problems);
            rig.stop();
            String log = rig.log().readAll();
            if (log != null && log.contains("world event bridge failed")) {
                problems.add("a bridge failure was logged: " + firstLineWith(log, "world event bridge failed"));
            }
            if (log != null && (log.contains("MTR-0021") || log.contains("MTR-0023") || log.contains("MTR-0057")
                    || log.contains("MTR-0118") || log.contains("MTR-0119"))) {
                problems.add("a rule diagnostic was logged: " + firstLineWith(log, "MTR-0"));
            }
        } catch (Exception e) {
            problems.add("scenario error: " + e);
        } finally {
            if (rig != null) {
                rig.close();
            }
        }
        return new HarnessResult(problems.isEmpty(), problems, -1, rig == null ? null : rig.workspace().root,
                rig == null ? List.of() : rig.tailLines());
    }

    private static void scenario(CrashRig rig, List<String> problems) throws Exception {
        // T14, no item: a rule document pushes the player who joins; the velocity is read back from the server
        expect(rig, problems, "join Dasher", "dprobe join Dasher", M + "DASHED Dasher");
        String velocity = rig.probe("vel", "Dasher");
        // yaw 0 faces +Z: forward 1.6 and up 0.3
        if (!velocity.contains("y=0.3") || !velocity.contains("z=1.6")) {
            problems.add("the dash did not move the player forward and up: " + velocity);
        }

        // T13: the excavator mines a flat 3 by 3 (the centre is the block the player mines); one block is protected
        rig.probe("join", "Alice");
        rig.command("materione give worldverbs:excavator Alice", "MATERIONE-GIVE-OK");
        rig.probe("fillblocks", "-2 150 -2 2 150 2 STONE");
        rig.probe("protect", "1 150 0");
        rig.probe("dig", "Alice 0 150 0");
        if (rig.log().awaitLine(M + "DUG 0 150 0", rig.commandTimeout()) == null) {
            problems.add("the break rule did not run with the broken block as its location (no 'DUG 0 150 0')");
        }
        for (String block : List.of("0 150 0", "-1 150 0", "0 150 1", "0 150 -1", "-1 150 -1", "-1 150 1", "1 150 1", "1 150 -1")) {
            material(rig, problems, block, "AIR", "a block of the 3 by 3 area");
        }
        material(rig, problems, "1 150 0", "STONE", "the protected block");
        material(rig, problems, "2 150 0", "STONE", "a block outside the area");
        material(rig, problems, "-2 150 0", "STONE", "a block outside the area");
        // the verb did not re-trigger the excavator for the blocks it broke: the rule ran once
        long digs = rig.log().readAll().lines().filter(line -> line.contains(M + "DUG") && line.contains("[Server]")).count();
        if (digs != 1) {
            problems.add("the excavator's rule ran " + digs + " times, not once");
        }
    }

    private static void material(CrashRig rig, List<String> problems, String block, String expected, String what)
            throws Exception {
        String line = rig.probe("block", block);
        if (!line.contains("type=" + expected)) {
            problems.add(what + " (" + block + ") is not " + expected + ": " + line);
        }
    }

    private static void expect(CrashRig rig, List<String> problems, String what, String command, String marker)
            throws Exception {
        try {
            rig.command(command, marker);
        } catch (IllegalStateException missing) {
            problems.add(what + ": " + missing.getMessage().lines().findFirst().orElse(""));
        }
    }

    private static String firstLineWith(String log, String fragment) {
        return log.lines().filter(line -> line.contains(fragment)).findFirst().orElse("");
    }
}
