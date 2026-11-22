package dev.materione.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The protection port on a real Paper server (logic.md §43, #466, docs/architecture/protection.md). The durability probe registers
 * a stand-in provider through the product's protection SPI ({@code dprobe portdeny}), and the {@code protection} fixture pack
 * names a pickaxe whose {@code break_blocks} (as the player) and {@code set_block} (with {@code as_player: false}) change the world,
 * and a rule that asks {@code can_build} and {@code can_break}.
 *
 * <p>Two boots. In the default {@code both} mode a block the port denies never reaches the player's own break path (no
 * {@code BlockBreakEvent} is fired for it), the other blocks are asked and then broken through the event path, an actor-less
 * {@code set_block} is asked with no actor and refused, and {@code can_build} reads the port. With {@code protection_mode: event}
 * the verbs ignore the port (the denied block breaks, the denied placement happens, nothing is asked), while the condition still
 * reads it.
 *
 * <p>The players are the fake-connection players of {@link CrashRig}. This proves the logic on Paper, where every thread check is
 * true; the cross-region behavior is proved at the {@code Regions} seam by unit tests and nothing here ran on Folia.
 */
public final class ProtectionRunner {

    private static final String M = "MATERIONE-PROTECTION-";

    public HarnessResult run(HarnessOptions options) {
        List<String> problems = new ArrayList<>();
        HarnessResult both = boot(options, "protection-both", null, problems, true);
        HarnessResult event = boot(options, "protection-event", "protection_mode: event\n", problems, false);
        List<String> tail = event.logTail().isEmpty() ? both.logTail() : event.logTail();
        return new HarnessResult(problems.isEmpty(), problems, -1, event.workDir() != null ? event.workDir() : both.workDir(),
                tail);
    }

    private HarnessResult boot(HarnessOptions options, String scenario, String settings, List<String> problems, boolean both) {
        CrashRig rig = null;
        try {
            rig = CrashRig.createForEscrow(options, scenario, null, Set.of("protection"), settings);
            rig.boot();
            if (both) {
                bothScenario(rig, problems);
            } else {
                eventScenario(rig, problems);
            }
            rig.stop();
            String log = rig.log().readAll();
            if (log == null) {
                problems.add(scenario + ": no server log");
            } else {
                String mode = both ? "both" : "event";
                if (!log.contains("MATERIONE-PROTECTION-OK mode=" + mode)) {
                    problems.add(scenario + ": the protection marker for mode " + mode + " was not logged");
                }
                if (!log.contains("protection stand-in registered")) {
                    problems.add(scenario + ": the stand-in provider did not register: " + firstLineWith(log, "stand-in"));
                }
                for (String code : List.of("MTR-0021", "MTR-0057", "MTR-0060", "MTR-0118", "MTR-0119", "MTR-0122", "MTR-0123",
                        "MTR-0124", "bridge failed")) {
                    if (log.contains(code)) {
                        problems.add(scenario + ": a diagnostic was logged (" + code + "): " + firstLineWith(log, code));
                    }
                }
            }
        } catch (Exception e) {
            problems.add(scenario + ": scenario error: " + e);
        } finally {
            if (rig != null) {
                rig.close();
            }
        }
        return new HarnessResult(problems.isEmpty(), problems, -1, rig == null ? null : rig.workspace().root,
                rig == null ? List.of() : rig.tailLines());
    }

    private static void bothScenario(CrashRig rig, List<String> problems) throws Exception {
        // can_build and can_break read the port: allowed while the stand-in allows
        rig.probe("join", "Mason");
        waitFor(rig, problems, M + "BUILD Mason yes", "can_build is true while no provider denies");
        waitFor(rig, problems, M + "BREAK Mason yes", "can_break (as nobody) is true while no provider denies");

        // a flat 3 by 3 as the player: the port denies one block to break and one to place above the broken one (no actor)
        rig.probe("join", "Alice");
        rig.command("materione give protection:port_pickaxe Alice", "MATERIONE-GIVE-OK");
        rig.probe("fillblocks", "-2 150 -2 2 150 2 STONE");
        rig.probe("portdeny", "1 150 0");
        rig.probe("portdeny", "0 151 0");
        rig.probe("dig", "Alice 0 150 0");
        waitFor(rig, problems, M + "DUG 0 150 0", "the break rule ran with the broken block as its location");
        material(rig, problems, "1 150 0", "STONE", "the block the port denied");
        for (String block : List.of("0 150 0", "-1 150 0", "0 150 1", "0 150 -1", "-1 150 -1", "-1 150 1", "1 150 1", "1 150 -1")) {
            material(rig, problems, block, "AIR", "a block the port allowed");
        }
        material(rig, problems, "2 150 0", "STONE", "a block outside the box");
        material(rig, problems, "0 151 0", "AIR", "the block above, whose actor-less placement the port denied");
        // the port answers before the event path: no break event was ever raised for the denied block, one was for the others
        count(rig, problems, "1 150 0", 0, "the denied block never reached the player's own break path");
        count(rig, problems, "1 150 1", 1, "an allowed block went through the player's own break path");
        String asked = rig.probe("portasked", "");
        if (!asked.contains("PLACE@0,151,0 nobody")) {
            problems.add("the actor-less set_block did not ask the port with no actor: " + asked);
        }

        // nothing denied now: the actor-less placement happens, asked with no actor
        rig.probe("portclear", "");
        rig.probe("fillblocks", "9 150 9 11 150 11 STONE");
        rig.probe("dig", "Alice 10 150 10");
        waitFor(rig, problems, M + "CAPPED 10 150 10", "the cap rule ran");
        material(rig, problems, "10 151 10", "GLASS", "the block placed with as_player: false when the port allows");
        asked = rig.probe("portasked", "");
        if (!asked.contains("PLACE@10,151,10 nobody")) {
            problems.add("the allowed actor-less placement was not asked of the port: " + asked);
        }

        // can_build reads the port: now it denies everything
        rig.probe("portdeny", "all");
        rig.probe("join", "Builder");
        waitFor(rig, problems, M + "BUILD Builder no", "can_build is false when a provider denies");
        waitFor(rig, problems, M + "BREAK Builder no", "can_break (as nobody) is false when a provider denies");
    }

    private static void eventScenario(CrashRig rig, List<String> problems) throws Exception {
        rig.probe("join", "Alice");
        rig.probe("portclear", ""); // the join rule asked can_build and can_break about Alice: only the verbs' questions count below
        rig.command("materione give protection:port_pickaxe Alice", "MATERIONE-GIVE-OK");
        rig.probe("fillblocks", "-2 150 -2 2 150 2 STONE");
        rig.probe("portdeny", "1 150 0");
        rig.probe("portdeny", "0 151 0");
        rig.probe("dig", "Alice 0 150 0");
        waitFor(rig, problems, M + "DUG 0 150 0", "the break rule ran");
        // protection_mode: event ignores the port: the denied block breaks through the event path and the placement happens
        material(rig, problems, "1 150 0", "AIR", "the block the port denied, which protection_mode: event never asks about");
        material(rig, problems, "0 151 0", "GLASS", "the placement the port denied, never asked in event mode");
        count(rig, problems, "1 150 0", 1, "the event path ran for the block the port would have denied");
        String asked = rig.probe("portasked", "");
        if (!asked.contains("count=0")) {
            problems.add("protection_mode: event still asked the port: " + asked);
        }

        // the condition is the author asking: it reads the port whatever the mode says
        rig.probe("portdeny", "all");
        rig.probe("join", "Builder");
        waitFor(rig, problems, M + "BUILD Builder no", "can_build reads the port even in event mode");
    }

    private static void waitFor(CrashRig rig, List<String> problems, String marker, String what) throws Exception {
        if (rig.log().awaitLine(marker, rig.commandTimeout()) == null) {
            problems.add(what + " (no '" + marker + "')");
        }
    }

    private static void material(CrashRig rig, List<String> problems, String block, String expected, String what)
            throws Exception {
        String line = rig.probe("block", block);
        if (!line.contains("type=" + expected)) {
            problems.add(what + " (" + block + ") is not " + expected + ": " + line);
        }
    }

    private static void count(CrashRig rig, List<String> problems, String block, int expected, String what) throws Exception {
        String line = rig.probe("breakseen", block);
        if (!line.contains("count=" + expected)) {
            problems.add(what + " (" + block + "): expected " + expected + " break event(s): " + line);
        }
    }

    private static String firstLineWith(String log, String fragment) {
        return log.lines().filter(line -> line.contains(fragment)).findFirst().orElse("");
    }
}
