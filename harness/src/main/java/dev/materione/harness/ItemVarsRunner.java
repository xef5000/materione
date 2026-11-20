package dev.materione.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Item-scoped variables on a real Paper server (logic.md section 38, #333): the state a stack holds in its own data. The
 * players are the fake-connection players of {@link CrashRig} (a real {@code ServerPlayer}, no socket), the entities come
 * from {@code summon}, and a player's hit is the console's {@code damage ... by <player>}, which raises the same
 * {@code EntityDamageByEntityEvent} a swing does. The rules are the {@code itemvars} fixture pack: the trained sword levels
 * itself up on every hit and announces the new level with a {@code say MATERIONE-ITEMVARS-...} line the runner reads from the
 * console log; {@code /materione var get} reads the level back from the stack in the player's main hand.
 *
 * <p>It proves, in order: two swords keep two levels (a copy made by vanilla's {@code /item replace} carries its state, a
 * fresh sword does not); the level survives a drop and a pick-up; stacks whose state differs do not merge and stacks in the
 * same state do (a level equal to the default clears the key); and the level survives a server restart.
 */
public final class ItemVarsRunner {

    private static final String M = "MATERIONE-ITEMVARS-";

    public HarnessResult run(HarnessOptions options) {
        List<String> problems = new ArrayList<>();
        CrashRig rig = null;
        try {
            rig = CrashRig.createForEscrow(options, "item-vars", null, Set.of("itemvars"));
            rig.boot();
            scenario(rig, problems);
            rig.stop();
            String log = rig.log().readAll();
            if (log != null && log.contains("behavior bridge failed")) {
                problems.add("a bridge failure was logged: " + firstLineWith(log, "behavior bridge failed"));
            }
            if (log != null && log.contains("world event bridge failed")) {
                problems.add("a bridge failure was logged: " + firstLineWith(log, "world event bridge failed"));
            }
            if (log != null && (log.contains("MTR-0060") || log.contains("MTR-0065") || log.contains("MTR-0023"))) {
                problems.add("a variable or rule diagnostic was logged: "
                        + firstLineWith(log, "MTR-006", "MTR-0023"));
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
        rig.probe("join", "Alice");
        rig.command("materione give itemvars:trained_sword Alice", "MATERIONE-GIVE-OK");
        rig.command("execute at Alice run summon husk ~ ~ ~ {NoAI:1b,Tags:[\"target\"]}", "Summoned");

        // two hits level the sword in the main hand: the run's item is the stack that was swung
        expect(rig, problems, "first hit", hit(), M + "HIT Alice level=2");
        expect(rig, problems, "second hit", hit(), M + "HIT Alice level=3");
        expect(rig, problems, "the level is read back from the stack", "materione var get Alice itemvars:level",
                "itemvars:level = 3");

        // a copy made by vanilla carries the state with it (the data is the stack's own); a fresh sword has its own
        rig.command("item replace entity Alice hotbar.2 from entity Alice weapon.mainhand", "Replaced");
        rig.command("materione give itemvars:trained_sword Alice", "MATERIONE-GIVE-OK");
        rig.command("item replace entity Alice weapon.mainhand from entity Alice hotbar.1", "Replaced");
        expect(rig, problems, "the fresh sword has the default", "materione var get Alice itemvars:level",
                "itemvars:level = 1");
        expect(rig, problems, "the fresh sword levels alone", hit(), M + "HIT Alice level=2");
        expect(rig, problems, "the fresh sword reads its own level", "materione var get Alice itemvars:level",
                "itemvars:level = 2");
        rig.command("item replace entity Alice weapon.mainhand from entity Alice hotbar.2", "Replaced");
        expect(rig, problems, "the first sword kept its level", "materione var get Alice itemvars:level",
                "itemvars:level = 3");

        // a drop and a pick-up: the item entity holds the same stack
        rig.probe("click", "Alice 36 0 THROW");
        String dropped = rig.probe("items", "");
        if (!dropped.contains("items count=1") || !dropped.contains("IRON_SWORD")) {
            problems.add("the sword was not dropped as one item entity: " + dropped);
        }
        rig.command("data merge entity @e[type=item,limit=1] {PickupDelay:0}", "Modified");
        rig.command("execute as Alice at @e[type=item,limit=1,sort=nearest] run tp @s ~ ~ ~", "Teleported");
        // a fake-connection player is not ticked into collecting items, so the probe makes the item entity touch it:
        // ItemEntity#playerTouch, the same path a walking player takes
        rig.probe("pickup", "Alice");
        String inventory = rig.probe("inv", "Alice");
        if (!inventory.contains("[0:IRON_SWORD*1")) {
            problems.add("the sword was not picked back up: " + inventory);
        }
        expect(rig, problems, "the level survives a drop and a pick-up", "materione var get Alice itemvars:level",
                "itemvars:level = 3");

        // stacking: tokens in different states do not merge, tokens in the same state do
        rig.command("item replace entity Alice weapon.mainhand with minecraft:air", "Replaced");
        rig.command("materione give itemvars:token Alice", "MATERIONE-GIVE-OK");
        expect(rig, problems, "a token is held", "materione var set Alice itemvars:level 5", "itemvars:level = 5");
        rig.command("materione give itemvars:token Alice", "MATERIONE-GIVE-OK");
        String apart = rig.probe("inv", "Alice");
        if (apart.contains("GOLD_NUGGET*2") || countOf(apart, "GOLD_NUGGET*1") != 2) {
            problems.add("tokens whose state differs must not stack, but the inventory is: " + apart);
        }
        expect(rig, problems, "back to the default clears the key", "materione var set Alice itemvars:level 1",
                "itemvars:level = 1");
        rig.command("materione give itemvars:token Alice", "MATERIONE-GIVE-OK");
        String together = rig.probe("inv", "Alice");
        if (!together.contains("GOLD_NUGGET*2") || countOf(together, "GOLD_NUGGET") != 2) {
            problems.add("tokens in the same state must stack (a default clears the key), but the inventory is: "
                    + together);
        }
        rig.command("clear Alice minecraft:gold_nugget", "Removed");

        // a restart: the level is in the player's saved inventory, in the stack
        rig.command("item replace entity Alice weapon.mainhand from entity Alice hotbar.2", "Replaced");
        expect(rig, problems, "the sword is back in the hand", "materione var get Alice itemvars:level",
                "itemvars:level = 3");
        rig.probe("leave", "Alice");
        rig.stop();
        rig.boot();
        rig.probe("join", "Alice");
        expect(rig, problems, "the level survives a restart", "materione var get Alice itemvars:level",
                "itemvars:level = 3");
        rig.command("execute at Alice run summon husk ~ ~ ~ {NoAI:1b,Tags:[\"target\"]}", "Summoned");
        expect(rig, problems, "and keeps counting after it", hit(), M + "HIT Alice level=4");

        // a rule document reads the stack in the hand when its trigger has none of its own
        Thread.sleep(1_500);
        expect(rig, problems, "a kill reads the level of the sword in hand",
                "damage @e[type=husk,tag=target,limit=1] 1000 minecraft:player_attack by Alice",
                M + "KILL Alice level=5");
    }

    private static String hit() throws InterruptedException {
        Thread.sleep(1_500); // the husk's hurt-invulnerability (ten ticks, more on a loaded host) would swallow a second hit
        return "damage @e[type=husk,tag=target,limit=1] 1 minecraft:player_attack by Alice";
    }

    /** Sends {@code command} and checks the marker arrives after it. */
    private static void expect(CrashRig rig, List<String> problems, String what, String command, String marker)
            throws Exception {
        try {
            rig.command(command, marker);
        } catch (IllegalStateException missing) {
            problems.add(what + ": " + missing.getMessage().lines().findFirst().orElse(""));
        }
    }

    private static int countOf(String text, String fragment) {
        int count = 0;
        for (int at = text.indexOf(fragment); at >= 0; at = text.indexOf(fragment, at + 1)) {
            count++;
        }
        return count;
    }

    private static String firstLineWith(String log, String... fragments) {
        return log.lines().filter(line -> {
            for (String fragment : fragments) {
                if (line.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }).findFirst().orElse("");
    }
}
