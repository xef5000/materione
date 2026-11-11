package dev.materione.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Probe P4 of gui-input-durability.md section 11 (row R1, #306) on the 1.21.4 floor: a fake-connection player (a real
 * {@code ServerPlayer} on a netty {@code EmbeddedChannel}, placed by {@code PlayerList.placeNewPlayer}) is saved by every
 * save path the design relies on: {@code Player#saveData}, the quit path, the shutdown path and (second boot) the Paper
 * per-player autosave; and it reloads from the same file on the next join. (Probe P2, a completed save surviving a kill,
 * and the K-row skeleton are now {@link EscrowCrashRunner}'s, row H: the same kills against the product's account.)
 */
public final class InputDurabilityRunner {

    /** Paper's per-player autosave, on every 20 ticks: used only by the autosave check, never by the K rows (they need none). */
    static final String AUTOSAVE_YAML = """
            player-auto-save:
              rate: 20
              max-per-tick: 10
            """;

    /** Problem collector: an assertion that records instead of throwing, so one run reports every failure. */
    static final class Problems {
        private final List<String> list = new ArrayList<>();
        private String context = "";

        void in(String context) {
            this.context = context;
        }

        void expect(boolean condition, String message) {
            if (!condition) {
                list.add(context + ": " + message);
            }
        }

        List<String> all() {
            return list;
        }
    }

    public HarnessResult platformPaths(HarnessOptions options) {
        Problems problems = new Problems();
        CrashRig rig = null;
        try {
            // Autosave off (the default): nothing but the path under test can write the file
            CrashRig started = CrashRig.create(options, "input-durability-paths", null);
            rig = started;
            started.boot();
            String p = "PathBot";
            problems.in("join");
            String joined = started.probe("join", p);
            problems.expect(joined.contains("ok=true"), "the fake player did not join: " + joined);
            problems.expect(!Files.exists(started.playerDataFile(p)), "a .dat exists before any save");
            String state = started.probe("state", p);
            problems.expect(state.contains("online=true") && state.contains("health=20.0"), "state: " + state);

            windowEvents(started, problems);

            problems.in("saveData");
            started.probe("pdc", p + " mark one");
            started.probe("save", p);
            problems.expect(markIs(started, p, "one"), "Player#saveData did not write the persistent data");
            problems.expect(Files.exists(started.playerDataFile(p)), "no .dat after saveData");

            problems.in("quit path (PlayerList.remove)");
            started.probe("pdc", p + " mark two");
            started.probe("leave", p);
            problems.expect(markIs(started, p, "two"), "the quit path did not save the player");

            problems.in("rejoin loads the file");
            started.probe("join", p);
            String reloaded = started.probe("pdc", p + " mark");
            problems.expect(reloaded.contains("value=two"), "the rejoined player did not load its .dat: " + reloaded);

            problems.in("shutdown path (saveAll with the player online)");
            started.probe("pdc", p + " mark three");
            int exit = started.stop();
            problems.expect(exit == 0, "non-zero exit " + exit);
            problems.expect(markIs(started, p, "three"), "the stop path did not save the online fake player");

            // The second boot turns the Paper player autosave on (rate 20 ticks) in the config the first boot read
            problems.in("the file the stop wrote reloads");
            Files.writeString(started.workspace().root.resolve("config").resolve("paper-global.yml"), AUTOSAVE_YAML);
            started.boot();
            started.probe("join", p);
            problems.expect(started.probe("pdc", p + " mark").contains("value=three"), "reload after stop lost the mark");

            problems.in("Paper autosave (PlayerList.saveAll(rate))");
            started.probe("pdc", p + " mark auto");
            RigHolder holder = new RigHolder(started, p);
            problems.expect(await(Duration.ofSeconds(30), () -> holder.markIs("auto")),
                    "the Paper autosave never wrote the fake player's later change");
            started.stop();
        } catch (Exception e) {
            problems.all().add("scenario error: " + e);
        } finally {
            if (rig != null) {
                rig.close();
            }
        }
        return result(rig, problems);
    }

    /**
     * P4, the inventory half: a click packet, a drag and a close, all through the player's own packet listener, each seen
     * by the Bukkit events a product listener would use. The player lifts five diamonds from the hotbar to the cursor
     * while a 27-slot window is open, spreads them over two window slots (a left drag: two each, one left on the cursor),
     * and the close returns the remainder (F4: the cursor goes back to the inventory on a close).
     */
    private static void windowEvents(CrashRig rig, Problems problems) throws Exception {
        String w = "WinBot";
        problems.in("click, drag and close through the packet handler");
        rig.probe("join", w);
        rig.probe("set", w + " 0 DIAMOND 5");
        problems.expect(rig.probe("open", w).contains("ok=true"), "the window did not open");
        int cursor = rig.log().mark();
        String click = rig.probe("click", w + " 54 0 PICKUP");
        problems.expect(click.contains("cursor=DIAMOND*5"), "the click packet did not lift the stack: " + click);
        String drag = rig.probe("drag", w + " 0 0,1");
        problems.expect(drag.contains("cursor=DIAMOND*1"), "the drag did not leave one diamond on the cursor: " + drag);
        String closed = rig.probe("close", w);
        problems.expect(closed.contains("ok=true"), "the close failed: " + closed);
        String log = rig.log().readAll();
        String events = log.substring(Math.min(cursor, log.length()));
        problems.expect(events.contains("event click player=" + w + " raw=54") && events.contains("holder=probe"),
                "no InventoryClickEvent for the lifted stack in the probe window");
        problems.expect(events.contains("event drag player=" + w + " rawSlots=[0, 1]"),
                "no InventoryDragEvent over window slots 0 and 1");
        problems.expect(events.contains("event close player=" + w) && events.contains("window=[DIAMOND*2,DIAMOND*2,]"),
                "the close event did not show two stacks of two in the window");
        String inv = rig.probe("inv", w);
        problems.expect(inv.contains("DIAMOND*1") && inv.contains("cursor=empty"),
                "the close did not return the remaining diamond from the cursor to the inventory: " + inv);
        // The player is left online on purpose: the stop path below saves it too
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** True when the player's saved persistent data holds the probe marker {@code value} (a string under {@code :mark}). */
    private static boolean markIs(CrashRig rig, String player, String value) throws IOException {
        return new RigHolder(rig, player).markIs(value);
    }

    private static Object value(PlayerDat dat, String suffix) {
        return dat.bukkitValues().entrySet().stream().filter(entry -> entry.getKey().endsWith(suffix))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /** Reads the player's saved marker; a file that is mid-replace or absent is simply "not yet". */
    private record RigHolder(CrashRig rig, String player) {
        boolean markIs(String value) {
            try {
                return value.equals(value(rig.savedData(player), ":mark"));
            } catch (IOException | RuntimeException notYet) {
                return false;
            }
        }
    }

    private static boolean await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(250);
        }
        return condition.getAsBoolean();
    }

    private static HarnessResult result(CrashRig rig, Problems problems) {
        return new HarnessResult(problems.all().isEmpty(), problems.all(), -1,
                rig == null ? null : rig.workspace().root, rig == null ? List.of() : rig.tailLines());
    }
}
