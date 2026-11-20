package dev.materione.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Target addressing on a real Paper server (logic.md §39, #331): an action acts on the entity a trigger carries, on a
 * player a flow names, and on what a bounded selector finds, and a target that does not resolve fails the action
 * instead of falling back to the run's own player.
 *
 * <p>The players are the fake-connection players of {@link CrashRig}, the entities come from {@code summon}, and a
 * player's hit is the console's {@code damage ... by <player>}. The rules are the {@code targets} fixture pack; each
 * announces itself with a {@code say MATERIONE-TARGETS-...} line, and the effect on the other entity is read back from
 * the server (its active effects), not from a marker alone. A message to a player cannot be read back without a client,
 * so it is proved by its outcome: the flow's {@code require} runs its else when the message fails and goes on when it
 * does not. What a player sees is the QA catalog's {@code targets} suite.
 *
 * <p>This proves the logic on Paper, where every thread check is true. The cross-region dispatch of the same code is
 * proved at the scheduler seam by {@code TargetedDispatchTest}; nothing here ran on Folia.
 */
public final class TargetAddressingRunner {

    private static final String M = "MATERIONE-TARGETS-";

    public HarnessResult run(HarnessOptions options) {
        List<String> problems = new ArrayList<>();
        CrashRig rig = null;
        try {
            rig = CrashRig.createForEscrow(options, "target-addressing", null, Set.of("targets"));
            rig.boot();
            scenario(rig, problems);
            rig.stop();
            String log = rig.log().readAll();
            if (log != null && log.contains("world event bridge failed")) {
                problems.add("a bridge failure was logged: " + firstLineWith(log, "world event bridge failed"));
            }
            if (log != null && (log.contains("MTR-0021") || log.contains("MTR-0023") || log.contains("MTR-0057"))) {
                problems.add("a rule diagnostic was logged: " + firstLineWith(log, "MTR-00"));
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
        // a message to a player who is not online fails the action, and the flow's require sees it (it does not
        // fall back to messaging Alice herself)
        expect(rig, problems, "a message to an offline player is refused", "dprobe join Alice",
                M + "MESSAGE-REFUSED Carol");
        // and to one who is online goes through: Bob's join messages Alice
        expect(rig, problems, "a message to an online player succeeds", "dprobe join Bob",
                M + "MESSAGE-OK Bob to Alice");

        // the bleed: a hit on a zombie puts the wither effect on the zombie that was struck (event.target)
        rig.command("execute at Alice run summon zombie ~ ~ ~ {NoAI:1b,Tags:[\"bleed\"]}", "Summoned");
        expect(rig, problems, "the struck zombie bleeds",
                "damage @e[type=zombie,tag=bleed,limit=1] 1 minecraft:player_attack by Alice", M + "BLEED zombie");
        effect(rig, problems, "@e[type=zombie,tag=bleed,limit=1]", "wither", "the struck zombie");
        rig.command("kill @e[type=zombie]", "Killed");

        // a bounded selector: striking a husk slows the nearest zombie within eight blocks, which is not the husk
        rig.command("execute at Alice run summon zombie ~ ~ ~ {NoAI:1b,Tags:[\"slowme\"]}", "Summoned");
        rig.command("execute at Alice run summon husk ~ ~ ~ {NoAI:1b,Tags:[\"struck\"]}", "Summoned");
        expect(rig, problems, "the selector finds the zombie",
                "damage @e[type=husk,tag=struck,limit=1] 1 minecraft:player_attack by Alice", M + "NEARBY-DONE");
        effect(rig, problems, "@e[type=zombie,tag=slowme,limit=1]", "slowness", "the nearest zombie");
    }

    /** Reads the active effects of the entity the selector names and checks {@code name} is among them. */
    private static void effect(CrashRig rig, List<String> problems, String selector, String name, String what)
            throws Exception {
        try {
            String effects = rig.command("data get entity " + selector + " active_effects", "entity data");
            if (!effects.contains(name)) {
                problems.add(what + " has no " + name + " effect: " + effects);
            }
        } catch (IllegalStateException missing) {
            problems.add(what + " has no active effects at all: " + missing.getMessage().lines().findFirst().orElse(""));
        }
    }

    /** Sends {@code command} and checks the marker arrives. */
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
