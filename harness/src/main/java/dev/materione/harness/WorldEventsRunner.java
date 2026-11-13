package dev.materione.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The world-event triggers on a real Paper server (logic.md §37, #327): a rule document with no host answers a
 * join, a kill, a hit, a cancel, a hurt, a death, a respawn and a quit; an item holds an {@code entity.hit} rule too.
 *
 * <p>The players are the fake-connection players of {@link CrashRig} (a real {@code ServerPlayer}, no socket), the
 * entities come from {@code summon}, and a player's hit is the console's {@code damage ... by <player>}, which raises
 * the same {@code EntityDamageByEntityEvent} a swing does and attributes a kill to the player. The rules are the
 * {@code worldrules} fixture pack; each announces itself with a {@code say MATERIONE-WORLDRULES-...} line the
 * runner reads from the console log. The headless harness proves the logic end to end; what a real client sees
 * (chat lines, a swing) is the QA catalog's {@code worldrules} suite.
 *
 * <p>The quest is data: the second zombie kill finishes it, counted in a declared player variable.
 */
public final class WorldEventsRunner {

    private static final String M = "MATERIONE-WORLDRULES-";

    public HarnessResult run(HarnessOptions options) {
        List<String> problems = new ArrayList<>();
        CrashRig rig = null;
        try {
            rig = CrashRig.createForEscrow(options, "world-events", null, Set.of("worldrules"));
            rig.boot();
            scenario(rig, problems);
            rig.stop();
            String log = rig.log().readAll();
            if (log != null && log.contains("world event bridge failed")) {
                problems.add("a bridge failure was logged: " + firstLineWith(log, "world event bridge failed"));
            }
            if (log != null && rulesDiagnosed(log)) {
                problems.add("a rule diagnostic was logged: " + firstLineWith(log, "MTR-002"));
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
        // the first join of a player greets with first=true (the player has no saved data)
        expect(rig, problems, "join greets", "dprobe join Alice", M + "JOIN Alice first=true");
        rig.command("materione give worldrules:rule_blade Alice", "MATERIONE-GIVE-OK");

        // a hit carries the entity and the damage; the filters (type zombie, cause entity_attack) hold
        rig.command("execute at Alice run summon zombie ~ ~ ~ {NoAI:1b,Tags:[\"target\"]}", "Summoned");
        String hit = rig.command("damage @e[type=zombie,tag=target,limit=1] 1 minecraft:player_attack by Alice",
                M + "HIT zombie cause=entity_attack");
        if (!hit.contains("amount=1")) {
            problems.add("the hit's damage.amount was not 1: " + hit);
        }
        // the item in the player's main hand answers the same trigger, with item.* describing it
        expect(rig, problems, "the held item answers entity.hit", null, M + "BLADE worldrules:rule_blade hit zombie");

        // a kill counts; the second one finishes the quest (a player variable, no engine code)
        expect(rig, problems, "first kill", "damage @e[type=zombie,tag=target,limit=1] 1000 minecraft:player_attack by Alice",
                M + "KILL zombie killer=Alice is_player=false");
        rig.command("execute at Alice run summon zombie ~ ~ ~ {NoAI:1b,Tags:[\"target\"]}", "Summoned");
        expect(rig, problems, "second kill", "damage @e[type=zombie,tag=target,limit=1] 1000 minecraft:player_attack by Alice",
                M + "QUEST-DONE Alice");

        // a rule can cancel the damage: the husk keeps all its health
        rig.command("execute at Alice run summon husk ~ ~ ~ {NoAI:1b,Tags:[\"cancelled\"]}", "Summoned");
        expect(rig, problems, "cancel", "damage @e[type=husk,tag=cancelled,limit=1] 5 minecraft:player_attack by Alice",
                M + "HUSK-CANCELLED");
        String health = rig.command("data get entity @e[type=husk,tag=cancelled,limit=1] Health", "entity data");
        if (!health.contains("20.0f")) {
            problems.add("a cancelled hit still hurt the husk: " + health);
        }

        // the player is hurt, dies and respawns; a player who has just joined is invulnerable for three seconds, so wait
        Thread.sleep(4_000);
        expect(rig, problems, "hurt", "damage Alice 1 minecraft:fall", M + "HURT Alice cause=fall");
        expect(rig, problems, "death", "dprobe die Alice", M + "DEATH Alice");
        expect(rig, problems, "respawn", "dprobe respawn Alice", M + "RESPAWN Alice");

        // leaving runs the quit rule before the player's parked runs end
        expect(rig, problems, "quit", "dprobe leave Alice", M + "QUIT Alice");
    }

    /** Sends {@code command} (or none, when the marker is already on its way) and checks the marker arrives. */
    private static void expect(CrashRig rig, List<String> problems, String what, String command, String marker)
            throws Exception {
        try {
            if (command == null) {
                if (rig.log().awaitLine(marker, rig.commandTimeout()) == null) {
                    problems.add(what + ": no '" + marker + "'");
                }
            } else {
                rig.command(command, marker);
            }
        } catch (IllegalStateException missing) {
            problems.add(what + ": " + missing.getMessage().lines().findFirst().orElse(""));
        }
    }

    /** A rule problem at load or run time: MTR-0021 (rule skipped), MTR-0022 (expression), MTR-0023 (action failed). */
    private static boolean rulesDiagnosed(String log) {
        return log.contains("MTR-0021") || log.contains("MTR-0022") || log.contains("MTR-0023");
    }

    private static String firstLineWith(String log, String fragment) {
        return log.lines().filter(line -> line.contains(fragment)).findFirst().orElse("");
    }
}
