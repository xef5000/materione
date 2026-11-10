package dev.materione.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Probe P3 of gui-input-durability.md section 11 (row R3, #306): what becomes of a stack a plugin releases from an open
 * window <em>around a player's death</em>, under both {@code keepInventory} values.
 *
 * <p>The fake-connection player of {@link CrashRig} holds an apple stack in its inventory and a diamond stack in an open
 * window (standing for an input slot), then {@code setHealth(0)} kills it. Two release strategies run, both in the probe
 * plugin:
 * <ul>
 *   <li>{@code close}: the shipped behaviour (the window's stacks go to the inventory on {@code InventoryCloseEvent}).
 *       {@code ServerPlayer.die} fires the death event first and closes the container after (F10), so this release
 *       happens after the death outcome was decided.</li>
 *   <li>{@code event}: the owner-decided design (section 14 Q10): in {@code PlayerDeathEvent}, with
 *       {@code getKeepInventory()} the stacks go to the inventory, otherwise they are appended to
 *       {@code event.getDrops()}.</li>
 * </ul>
 * and each is observed through the event order, the drops in the world, and the respawned player's inventory. The
 * observations are written to {@code death-evidence.txt} in the workspace; the expectations below are the measured
 * results, so a platform change that moves any of them fails the scenario.
 */
public final class DeathProbeRunner {

    /** One measured case: the strategy, the gamerule, and what the world and the respawned player hold afterwards. */
    record Case(String mode, boolean keepInventory, String player, boolean diamondInWorldDrops, boolean appleInWorldDrops,
            boolean diamondAfterRespawn, boolean appleAfterRespawn) {}

    /** Measured on Paper 1.21.4 build 232 (see gui-input-durability.md section 11, P3). */
    static final List<Case> EXPECTED = List.of(
            new Case("close", false, "CloseOff", false, true, false, false),
            new Case("close", true, "CloseOn", false, false, true, true),
            new Case("event", false, "EventOff", true, true, false, false),
            new Case("event", true, "EventOn", false, false, true, true));

    public HarnessResult run(HarnessOptions options) {
        List<String> problems = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        CrashRig rig = null;
        try {
            rig = CrashRig.create(options, "input-durability-death", null);
            rig.boot();
            for (Case expected : EXPECTED) {
                Case actual = measure(rig, expected, evidence);
                if (!actual.equals(expected)) {
                    problems.add("mode=" + expected.mode() + " keepInventory=" + expected.keepInventory()
                            + ": expected " + expected + " but measured " + actual);
                }
            }
            rig.stop();
        } catch (Exception e) {
            problems.add("scenario error: " + e);
        } finally {
            if (rig != null) {
                rig.close();
                try {
                    Files.write(rig.workspace().root.resolve("death-evidence.txt"), evidence, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // the evidence file is a convenience; the problems list is the verdict
                }
            }
        }
        return new HarnessResult(problems.isEmpty(), problems, -1, rig == null ? null : rig.workspace().root,
                rig == null ? List.of() : rig.tailLines());
    }

    private static Case measure(CrashRig rig, Case c, List<String> evidence) throws Exception {
        String p = c.player();
        rig.command("gamerule keepInventory " + c.keepInventory(), "keepInventory");
        rig.command("kill @e[type=item]", "");
        rig.probe("mode", c.mode());
        rig.probe("join", p);
        rig.probe("set", p + " 0 APPLE 3");
        rig.probe("open", p);
        rig.probe("win", p + " 0 DIAMOND 5");
        int cursor = rig.log().mark();
        rig.probe("die", p);
        String dropsLine = rig.probe("items", "");
        rig.probe("respawn", p);
        String inv = rig.probe("inv", p);

        evidence.add("=== mode=" + c.mode() + " keepInventory=" + c.keepInventory() + " player=" + p);
        String log = rig.log().readAll();
        for (String line : log.substring(Math.min(cursor, log.length())).split("\n")) {
            if (line.contains(CrashRig.MARKER)) {
                evidence.add(line.substring(line.indexOf(CrashRig.MARKER)).trim());
            }
        }
        // After the respawn the old window is gone; what the new player holds is the end of the story
        return new Case(c.mode(), c.keepInventory(), p,
                dropsLine.contains("DIAMOND*5"), dropsLine.contains("APPLE*3"),
                inv.contains("DIAMOND*5"), inv.contains("APPLE*3"));
    }
}
