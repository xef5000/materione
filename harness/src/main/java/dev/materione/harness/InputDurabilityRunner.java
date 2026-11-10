package dev.materione.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Probes P4 and P2 of gui-input-durability.md section 11 (row R1, #306) on the 1.21.4 floor, and the K-row skeleton
 * that row H (#313) fills in.
 *
 * <ul>
 *   <li>{@link #platformPaths}: P4. A fake-connection player (a real {@code ServerPlayer} on a netty
 *       {@code EmbeddedChannel}, placed by {@code PlayerList.placeNewPlayer}) is saved by every save path the design
 *       relies on: {@code Player#saveData}, the quit path, the shutdown path and (second boot) the Paper per-player
 *       autosave; and it reloads from the same file on the next join.</li>
 *   <li>{@link #kRows}: P2 and the skeleton. A row runs steps against the live server, parks the main thread at a named
 *       crash gate, kills the JVM there, reads the player's {@code .dat} from the harness, boots the same workspace and
 *       asks the rejoined player what it holds. The four rows here are the platform half of K1, K2, K4 and K21 (the
 *       probe plugin's persistent-data account stands in for the escrow account); row H replaces the stand-in with the
 *       product's and adds K3, K5 to K20 and K22 to K23.</li>
 * </ul>
 */
public final class InputDurabilityRunner {

    /** Paper's per-player autosave, on every 20 ticks: used only by the autosave check, never by the K rows (they need none). */
    static final String AUTOSAVE_YAML = """
            player-auto-save:
              rate: 20
              max-per-tick: 10
            """;

    /** What a row does before the kill, where it kills, and what it expects on disk and after the reboot. */
    interface Step {
        void run(CrashRig rig, String player) throws Exception;
    }

    interface Check {
        void check(CrashRig rig, String player, Problems problems) throws Exception;
    }

    /** One K row: the row id of section 5.10, the steps, the gate, the disk state after the kill, the state after boot. */
    record KRow(String id, String player, String gate, Step prepare, Step afterKill, Check disk, Check boot) {}

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

    /** The four K rows, chained so one reboot is both the check of a row and the start of the next. */
    public HarnessResult kRows(HarnessOptions options) {
        Problems problems = new Problems();
        CrashRig rig = null;
        try {
            rig = CrashRig.create(options, "input-durability-krows", null);
            rig.boot();
            List<KRow> rows = rows();
            for (int i = 0; i < rows.size(); i++) {
                KRow row = rows.get(i);
                problems.in(row.id());
                row.prepare().run(rig, row.player());
                rig.killAtGate(row.gate());
                problems.expect(!rig.alive(), "the server survived the kill");
                if (row.afterKill() != null) {
                    row.afterKill().run(rig, row.player());
                }
                row.disk().check(rig, row.player(), problems);
                rig.boot();
                row.boot().check(rig, row.player(), problems);
            }
            rig.stop();
        } catch (Exception e) {
            problems.all().add("scenario error: " + e);
        } finally {
            if (rig != null) {
                rig.close();
            }
        }
        return result(rig, problems);
    }

    // ---- the rows ---------------------------------------------------------------------------------------------------

    /** The shared start: the player joins, holds five diamonds in slot 0 and an apple stack in slot 1, and a save fixes S. */
    private static void seed(CrashRig rig, String p) throws Exception {
        rig.probe("join", p);
        rig.probe("set", p + " 0 DIAMOND 5");
        rig.probe("set", p + " 1 APPLE 3");
        rig.probe("save", p);
    }

    static List<KRow> rows() {
        return List.of(
                // K1: a tier-1 move-in whose save never happened. The older snapshot is self-consistent (F3): the stack is
                // still in the inventory on disk and after the boot, and no account was ever written.
                new KRow("K1 move-in, no save since", "K1Bot", "k1-after-move-in",
                        (rig, p) -> {
                            seed(rig, p);
                            rig.probe("movein", p + " 0");
                        },
                        null,
                        (rig, p, problems) -> {
                            PlayerDat dat = rig.savedData(p);
                            problems.expect("minecraft:diamond x5".equals(dat.inventory().get(0)),
                                    "disk S should still hold the diamonds in slot 0: " + dat.inventory());
                            problems.expect(!hasKey(dat, ":account"), "disk S has an account: " + dat.bukkitValues().keySet());
                        },
                        (rig, p, problems) -> {
                            String inv = rig.probe("join", p) + rig.probe("inv", p);
                            problems.expect(inv.contains("0:DIAMOND*5") && inv.contains("account=none"),
                                    "after boot the stack should be in the inventory only: " + inv);
                        }),
                // K2: the same move-in, then a save completed. The snapshot holds the stack in the account and not in the
                // inventory, from the same instant (F3): nothing is lost, the account is what recovery would return.
                new KRow("K2 move-in, then a save", "K2Bot", "k2-after-save",
                        (rig, p) -> {
                            seed(rig, p);
                            rig.probe("movein", p + " 0");
                            rig.probe("save", p);
                        },
                        null,
                        (rig, p, problems) -> {
                            PlayerDat dat = rig.savedData(p);
                            problems.expect(!dat.inventory().containsKey(0), "disk S' still has slot 0: " + dat.inventory());
                            problems.expect("minecraft:apple x3".equals(dat.inventory().get(1)),
                                    "the bystander stack changed: " + dat.inventory());
                            problems.expect(hasKey(dat, ":account"), "disk S' has no account: " + dat.bukkitValues().keySet());
                            problems.expect(Long.valueOf(1).equals(value(dat, ":gen")),
                                    "gen should be 1: " + dat.bukkitValues());
                        },
                        (rig, p, problems) -> {
                            String inv = rig.probe("join", p) + rig.probe("inv", p);
                            problems.expect(!inv.contains("0:DIAMOND") && inv.contains("account=[DIAMOND*5,]")
                                            && inv.contains("gen=1"),
                                    "after boot the stack should be owed by the account only: " + inv);
                        }),
                // K4 (simulated): a kill between safeReplaceFile's two moves leaves .dat_old = the previous snapshot and no
                // .dat (F2). The server cannot be stopped inside that call from a plugin, so the harness reproduces the file
                // state the kill would leave and checks the claim that matters: the next boot loads .dat_old (S), whole.
                new KRow("K4 kill between the two renames (file state simulated)", "K4Bot", "k4-after-save",
                        (rig, p) -> {
                            seed(rig, p);
                            rig.probe("movein", p + " 0");
                            rig.probe("save", p);
                        },
                        (rig, p) -> {
                            Files.delete(rig.playerDataFile(p));
                        },
                        (rig, p, problems) -> {
                            Path old = rig.playerDataFile(p).resolveSibling(rig.playerDataFile(p).getFileName() + "_old");
                            problems.expect(Files.exists(old), "no .dat_old next to the deleted .dat");
                            PlayerDat dat = PlayerDat.read(old);
                            problems.expect("minecraft:diamond x5".equals(dat.inventory().get(0)),
                                    ".dat_old should be the previous complete snapshot S: " + dat.inventory());
                        },
                        (rig, p, problems) -> {
                            String inv = rig.probe("join", p) + rig.probe("inv", p);
                            problems.expect(inv.contains("0:DIAMOND*5") && inv.contains("account=none"),
                                    "boot should fall back to .dat_old (S): " + inv);
                        }),
                // K21: a stack on the cursor at a save. A real click packet (the path a client takes) lifts the hotbar stack
                // to the cursor while a window is open; a save completes; the kill follows. The cursor is in no save (F4),
                // so the stack is gone from disk and from the rebooted player: the one row where the answer is "loss" (G5).
                new KRow("K21 pickup to the cursor, then a save", "K21Bot", "k21-after-save",
                        (rig, p) -> {
                            seed(rig, p);
                            rig.probe("open", p);
                            // Raw slot 54 is the player's hotbar slot 0 in a 27-slot chest window (27 + 27 main + 0)
                            String click = rig.probe("click", p + " 54 0 PICKUP");
                            if (!click.contains("cursor=DIAMOND*5")) {
                                throw new IllegalStateException("the click packet did not lift the stack: " + click);
                            }
                            rig.probe("save", p);
                        },
                        null,
                        (rig, p, problems) -> {
                            PlayerDat dat = rig.savedData(p);
                            problems.expect(!dat.inventory().containsKey(0),
                                    "the cursor stack must not be in the saved inventory: " + dat.inventory());
                            problems.expect("minecraft:apple x3".equals(dat.inventory().get(1)),
                                    "the bystander stack changed: " + dat.inventory());
                            problems.expect(!dat.root().containsKey("CarriedItem") && !dat.root().containsKey("Cursor"),
                                    "a cursor key exists: " + dat.root().keySet());
                        },
                        (rig, p, problems) -> {
                            String inv = rig.probe("join", p) + rig.probe("inv", p);
                            problems.expect(!inv.contains("DIAMOND") && inv.contains("1:APPLE*3"),
                                    "after boot the cursor stack should be lost (G5): " + inv);
                        }));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** True when the player's saved persistent data holds the probe marker {@code value} (a string under {@code :mark}). */
    private static boolean markIs(CrashRig rig, String player, String value) throws IOException {
        return new RigHolder(rig, player).markIs(value);
    }

    private static boolean hasKey(PlayerDat dat, String suffix) {
        return dat.bukkitValues().keySet().stream().anyMatch(key -> key.endsWith(suffix));
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
