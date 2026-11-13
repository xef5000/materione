package dev.materione.harness;

import dev.materione.harness.InputDurabilityRunner.Problems;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The kill-boundary integration test of gui-input-durability.md section 5.10 (row H of #247, issue #313): K1 to K22 on a
 * real headless Paper server, with the product's own runtime (the {@code guilogic:escrow} GUI: a one-row chest whose
 * slots are a {@code durable: required} input layer), the product's own crash gate ({@code CrashGate}, named points
 * inside the escrow code that park a thread under {@code -Dmaterione.escrow.gate}) and a real {@code kill -9}
 * ({@code destroyForcibly} on the whole process tree), K1 to K23 (K23 is the barrier, row B, #311).
 *
 * <p>A <b>row</b> runs against a live server: a fake-connection player (row R's rig) joins through the real pre-login
 * preload and the real join handler, plays through real click packets, and the server is killed either <em>at a
 * production gate</em> (the thread parks inside the escrow code at the named point and the harness kills the tree on
 * seeing the marker) or at a quiescent point (the {@code claim-done} gate of {@code /materione claim}, which touches no
 * state). The harness then reads the player's {@code .dat} itself ({@link PlayerDat}) and the ledger, boots the
 * <em>same world</em>, rejoins the player and asks what it holds. Every row asserts the guarantees G1 (conservation:
 * every stack exactly once, inventory or owed), G2 (no resurrection), G4 (no world write: no dropped item entity) and
 * I6 (recovery is idempotent) against the disk and the rebooted player, never against the plugin's own opinion.
 *
 * <p><b>What is real and what is crafted.</b> Everything of tier 1 (K1 to K9, K15 to K16, K19 to K22) is the product,
 * end to end. <b>Tier 2 (the consume exit, K10 to K14, K17) and the barrier (K23) are the product too, since #248:</b>
 * a real click on a button of the {@code guilogic:consume} GUI runs the real flow {@code custody.consume}, which freezes
 * the real held entry, appends the real INTENT through the real ledger writer, deletes the entry, and (for
 * {@code await: saved}) makes the real barrier call {@code Player.saveData()} and read the file back. The kill lands at
 * production gates between those steps ({@code exit-begun}, {@code ledger-before-append}, {@code ledger-after-append},
 * {@code exit-applied}, {@code barrier-saved}); the two ledger gates park the writer's IO thread, not the main one, so
 * the harness can save or quit the player while the exit is stopped half-way. The only crafted states left are the files
 * a kill <em>inside a write</em> would leave: K3 and K4 (a kill inside the platform's own save: the gate cannot sit
 * inside {@code PlayerDataStorage.save}), a torn INTENT or DONE record (K10c, K17: the bytes of the real record, cut
 * short and appended after the kill, by {@link EscrowFiles}, an independent implementation of the layout), and the
 * platform save that cannot land (K23b: the {@code .dat_old} the server renames onto is made a non-empty directory).
 *
 * <p>Rows are chained inside one workspace: the boot that checks a row is the server the next row starts on, so a
 * group of N rows costs N+1 boots (about 25 s each). The groups ({@link #core}, {@link #tier1},
 * {@link #deathAndDisable}, {@link #ledger}, {@link #cursorAndFiles}, {@link #autosave}) are separate scenarios so a
 * failure in one does not hide the others; only {@link #core} is in the default run.
 */
public final class EscrowCrashRunner {

    /** The gate names of {@code CrashGate.Gates} (the harness depends on no product module). */
    static final String ACCOUNT_WRITTEN = "account-written";
    static final String CLICK_APPLIED = "click-applied";
    static final String RELEASE_DONE = "release-done";
    static final String RECOVERY_BEFORE_COMMIT = "recovery-before-commit";
    static final String RECOVERY_DONE = "recovery-done";
    static final String DEATH_RELEASED = "death-released";
    static final String CLAIM_DONE = "claim-done";
    static final String EXIT_BEGUN = "exit-begun";
    static final String EXIT_APPLIED = "exit-applied";
    static final String LEDGER_BEFORE_APPEND = "ledger-before-append";
    static final String LEDGER_AFTER_APPEND = "ledger-after-append";
    static final String BARRIER_SAVED = "barrier-saved";

    /** The GUIs of the {@code guilogic} pack the rows open: escrow (no flow), consume (buttons) and trash (the recipe). */
    static final String ESCROW_GUI = "guilogic:escrow";
    static final String CONSUME_GUI = "guilogic:consume";
    static final String TRASH_GUI = "guilogic:trash";

    /** The consume GUI's buttons: slot 7 consumes without an effect, slot 8 waits for the save and then runs one. */
    static final int PLAIN_BUTTON = 7;
    static final int PAID_BUTTON = 8;
    static final String PAID_MARKER = "MATERIONE-GUICONSUME-PAID";

    /** The barrier waits 1 s between saves in the barrier group, so a row does not sit out the default 5 s. */
    private static final String FAST_BARRIER = "escrow_barrier_cooldown_seconds: 1\n";

    /** The escrow GUI's pack, and Paper's per-player autosave on every tick (K19 only). */
    private static final Set<String> PACKS = Set.of("guilogic");
    private static final String AUTOSAVE_EVERY_TICK = """
            player-auto-save:
              rate: 1
              max-per-tick: 10
            """;
    private static final Pattern INV = Pattern.compile(
            "slots=\\[(.*?)] cursor=(\\S+) window=\\[(.*?)] escrow=(\\S+)");

    /** One row of the table, run against a {@link Session}. */
    interface Row {
        void run(Session s) throws Exception;
    }

    // ---- the groups -------------------------------------------------------------------------------------------------

    /**
     * The default run: the rows that carry the most per boot. K1 (mid-step), K2, K5, K11 (a real consume killed with its
     * INTENT durable and the entry EXITING on disk: G2) and K18 (fail closed).
     */
    public HarnessResult core(HarnessOptions options) {
        Map<String, Row> rows = new LinkedHashMap<>();
        rows.put("K1 kill inside a move-in step", Session::k1MidStep);
        rows.put("K2 move-in then a save", Session::k2);
        rows.put("K5 move-out, no save since", Session::k5);
        rows.put("K11 real consume: INTENT durable, E EXITING on disk", Session::k11);
        rows.put("K18 corrupt ledger, corrupt and newer account", Session::k18);
        return run(options, "escrow-crash-core", null, rows);
    }

    /** Opt-in: the rest of tier 1 (K1 after the step, K6, K7, K8). */
    public HarnessResult tier1(HarnessOptions options) {
        Map<String, Row> rows = new LinkedHashMap<>();
        rows.put("K1 kill after a move-in step", Session::k1AfterStep);
        rows.put("K6 move-out then a save", Session::k6);
        rows.put("K7 close with a full inventory (overflow stays owed)", Session::k7);
        rows.put("K8a kill during a quit, before the save", Session::k8a);
        rows.put("K8b quit completed", Session::k8b);
        rows.put("K8c server stop with a window open", Session::k8c);
        return run(options, "escrow-crash-tier1", null, rows);
    }

    /** Opt-in: death (K9) and plugin disable (K20). */
    public HarnessResult deathAndDisable(HarnessOptions options) {
        Map<String, Row> rows = new LinkedHashMap<>();
        rows.put("K9a death, keepInventory off, killed in the event", Session::k9a);
        rows.put("K9b death, keepInventory on, killed in the event", Session::k9b);
        rows.put("K9c death completed, keepInventory off", Session::k9c);
        rows.put("K9d death completed, keepInventory on", Session::k9d);
        rows.put("K20a plugin disable, killed in the release", Session::k20a);
        rows.put("K20b plugin disable completed", Session::k20b);
        return run(options, "escrow-crash-death", null, rows);
    }

    /** Opt-in: the real consume killed before and after each of its steps (K10, K11 rolled back, K12, K13). */
    public HarnessResult ledger(HarnessOptions options) {
        Map<String, Row> rows = new LinkedHashMap<>();
        rows.put("K10a kill after step 1, nothing queued", Session::k10a);
        rows.put("K10b kill before the INTENT is written, E EXITING saved", Session::k10b);
        rows.put("K10c INTENT torn mid-append (file state)", Session::k10c);
        rows.put("K11 INTENT durable, snapshot predates E (G3)", Session::k11Rollback);
        rows.put("K12 step 3 ran, no save since", Session::k12);
        rows.put("K13 consume completed, then a save", Session::k13);
        return run(options, "escrow-crash-ledger", null, rows);
    }

    /** Opt-in: quit during a consume (K14), recovery (K15, K16), a torn tail (K17) and the trash composition. */
    public HarnessResult exits(HarnessOptions options) {
        Map<String, Row> rows = new LinkedHashMap<>();
        rows.put("K14a quit after the INTENT, before step 3", Session::k14a);
        rows.put("K14b quit before the INTENT lands", Session::k14b);
        rows.put("K15/K16 recovery killed, then a second crash after a saved recovery", Session::k15);
        rows.put("K17 INTENT then a torn tail (file state)", Session::k17);
        rows.put("KT the trash slot, a composition of custody.consume", Session::kTrash);
        return run(options, "escrow-crash-exits", null, rows);
    }

    /**
     * Opt-in: K23, the barrier (tier 3). A consume that carries an effect (the console marker) must never run it before a
     * saved snapshot containing the consume is read back from disk: killed between {@code saveData()} and the read-back
     * (a), a read-back that finds an older snapshot (b), the effect after the proof (c) and after a disk that recovers (d).
     */
    public HarnessResult barrier(HarnessOptions options) {
        Map<String, Row> rows = new LinkedHashMap<>();
        rows.put("K23a kill between saveData() and the read-back", Session::k23a);
        rows.put("K23b the read-back finds an older snapshot, then a kill", Session::k23b);
        rows.put("K23c the effect runs once, after the proof", Session::k23c);
        rows.put("K23d the disk recovers: the effect runs once, late", Session::k23d);
        return run(options, "escrow-crash-barrier", null, FAST_BARRIER, rows);
    }

    /** Opt-in: the cursor (K21, K22) and the kills inside the platform's save (K3, K4: file states). */
    public HarnessResult cursorAndFiles(HarnessOptions options) {
        Map<String, Row> rows = new LinkedHashMap<>();
        rows.put("K21a pickup to the cursor, then a save", Session::k21a);
        rows.put("K21b pickup to the cursor, no save", Session::k21b);
        rows.put("K22a a stack on the cursor at a save", Session::k22a);
        rows.put("K22b placement then a save", Session::k22b);
        rows.put("K3 stray temp file beside the snapshot", Session::k3);
        rows.put("K4 .dat missing, .dat_old only", Session::k4);
        return run(options, "escrow-crash-cursor", null, rows);
    }

    /** Opt-in: K19, with the Paper autosave on every tick: no snapshot is ever half a click. */
    public HarnessResult autosave(HarnessOptions options) {
        Map<String, Row> rows = new LinkedHashMap<>();
        rows.put("K19 autosave between clicks", Session::k19);
        return run(options, "escrow-crash-autosave", AUTOSAVE_EVERY_TICK, rows);
    }

    private HarnessResult run(HarnessOptions options, String scenario, String paperYaml, Map<String, Row> rows) {
        return run(options, scenario, paperYaml, null, rows);
    }

    private HarnessResult run(HarnessOptions options, String scenario, String paperYaml, String settings,
            Map<String, Row> rows) {
        Problems problems = new Problems();
        CrashRig rig = null;
        try {
            rig = CrashRig.createForEscrow(options, scenario, paperYaml, PACKS, settings);
            rig.boot();
            Session session = new Session(rig, problems);
            for (Map.Entry<String, Row> row : rows.entrySet()) {
                problems.in(row.getKey());
                try {
                    row.getValue().run(session);
                } catch (Exception failure) {
                    problems.expect(false, "row aborted: " + failure);
                }
                if (!rig.alive()) {
                    rig.boot(); // the next row needs a server whatever happened to this one
                }
            }
            if (rig.alive()) {
                rig.stop();
            }
        } catch (Exception e) {
            problems.all().add("scenario error: " + e);
        } finally {
            if (rig != null) {
                rig.close();
            }
        }
        return new HarnessResult(problems.all().isEmpty(), problems.all(), -1,
                rig == null ? null : rig.workspace().root, rig == null ? List.of() : rig.tailLines());
    }

    // ---- what a row sees --------------------------------------------------------------------------------------------

    /** The probe's {@code inv} answer: the player's inventory, cursor, open window and the escrow account (hex). */
    record Snap(Map<Integer, String> slots, String cursor, Map<Integer, String> window, String escrowHex) {

        int count(String material) {
            return total(slots.values(), material);
        }

        /** The account in the player's live data; null when there is none. */
        EscrowFiles.Account account() {
            return "none".equals(escrowHex) ? null : EscrowFiles.parseAccountHex(escrowHex);
        }

        int entries() {
            EscrowFiles.Account account = account();
            return account == null ? 0 : account.entries().size();
        }
    }

    /** What the player's {@code .dat} holds, as the harness reads it. */
    record Disk(PlayerDat dat, byte[] accountBytes) {

        int count(String id) {
            int total = 0;
            for (String stack : dat.inventory().values()) {
                if (stack.startsWith(id + " x")) {
                    total += Integer.parseInt(stack.substring(stack.indexOf(" x") + 2));
                }
            }
            return total;
        }

        EscrowFiles.Account account() {
            return accountBytes == null ? null : EscrowFiles.parseAccount(accountBytes);
        }

        int entries() {
            EscrowFiles.Account account = account();
            return account == null ? 0 : account.entries().size();
        }

        EscrowFiles.Entry sole() {
            EscrowFiles.Account account = account();
            return account == null || account.entries().size() != 1 ? null : account.entries().get(0);
        }
    }

    private static int total(Iterable<String> stacks, String material) {
        int total = 0;
        for (String stack : stacks) {
            if (stack.startsWith(material + "*")) {
                total += Integer.parseInt(stack.substring(stack.indexOf('*') + 1));
            }
        }
        return total;
    }

    private static Map<Integer, String> parseSlots(String text) {
        Map<Integer, String> slots = new LinkedHashMap<>();
        for (String part : text.split(",")) {
            if (!part.isBlank()) {
                int colon = part.indexOf(':');
                slots.put(Integer.parseInt(part.substring(0, colon)), part.substring(colon + 1));
            }
        }
        return slots;
    }

    /** The server, the problems, and every move a row makes. Rows are methods so each reads as its line of section 5.10. */
    static final class Session {

        private final CrashRig rig;
        private final Problems problems;
        private final Map<String, Integer> landing = new java.util.HashMap<>();

        Session(CrashRig rig, Problems problems) {
            this.rig = rig;
            this.problems = problems;
        }

        // ---- the moves --------------------------------------------------------------------------------------------

        /** The real pre-login (the product preloads the ledger) and the real join (the product recovers). */
        void login(String p) throws IOException, InterruptedException {
            String line = rig.command("dprobe login " + p, CrashRig.MARKER + "join ok=");
            if (!line.contains("ok=true")) {
                throw new IllegalStateException("login failed: " + line);
            }
        }

        void open(String p) throws IOException, InterruptedException {
            open(p, ESCROW_GUI);
        }

        void open(String p, String gui) throws IOException, InterruptedException {
            rig.command("materione gui open " + gui + " " + p, "player=" + p + " container=");
        }

        void set(String p, int slot, String material, int amount) throws IOException, InterruptedException {
            rig.probe("set", p + " " + slot + " " + material + " " + amount);
        }

        void click(String p, int raw, String type) throws IOException, InterruptedException {
            rig.probe("click", p + " " + raw + " 0 " + type);
        }

        /**
         * A shift-click of the player's diamonds into the window. After the seed they are in hotbar slot 0, which is raw
         * slot 36 of the one-row chest (inventory slots 9 to 35 are raw 9 to 35, the hotbar raw 36 to 44); after a
         * move-out the product puts them wherever vanilla's shift-click would, so the slot is read from the player.
         */
        void shiftIn(String p) throws IOException, InterruptedException {
            int raw = 36;
            for (Map.Entry<Integer, String> slot : inv(p).slots().entrySet()) {
                if ("DIAMOND*5".equals(slot.getValue())) {
                    raw = slot.getKey() < 9 ? 36 + slot.getKey() : slot.getKey();
                }
            }
            click(p, raw, "QUICK_MOVE");
        }

        /** A shift-click of the window's first slot into the inventory. */
        void shiftOut(String p) throws IOException, InterruptedException {
            click(p, slot(p), "QUICK_MOVE");
        }

        void save(String p) throws IOException, InterruptedException {
            rig.probe("save", p);
        }

        Snap inv(String p) throws IOException, InterruptedException {
            String line = rig.probe("inv", p);
            Matcher m = INV.matcher(line);
            if (!m.find()) {
                throw new IllegalStateException("unreadable inv answer: " + line);
            }
            return new Snap(parseSlots(m.group(1)), m.group(2), parseSlots(m.group(3)), m.group(4));
        }

        int droppedItems() throws IOException, InterruptedException {
            String line = rig.probe("items", "");
            Matcher m = Pattern.compile("count=(\\d+)").matcher(line);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        }

        /** The common start: the player joins, holds 5 diamonds in hotbar 0 and 3 apples in 1, opens the GUI, a save fixes S. */
        void seed(String p) throws IOException, InterruptedException {
            seed(p, ESCROW_GUI);
        }

        void seed(String p, String gui) throws IOException, InterruptedException {
            login(p);
            set(p, 0, "DIAMOND", 5);
            set(p, 1, "APPLE", 3);
            open(p, gui);
            save(p);
        }

        /** The start of every consume row: the stack is in custody in slot 0 of the consume GUI and a save holds it HELD (S'). */
        void heldAndSaved(String p) throws IOException, InterruptedException {
            seed(p, CONSUME_GUI);
            placeIn(p, 0);
            save(p);
        }

        /** The console command that presses a button of the consume GUI with a real click packet. */
        String press(String p, int button) {
            return "dprobe click " + p + " " + button + " 0 PICKUP";
        }

        void pressNow(String p, int button) throws IOException, InterruptedException {
            click(p, button, "PICKUP");
        }

        /** The saved account's frozen entry, which must be the only one. */
        EscrowFiles.Entry exiting(String p, String what) throws IOException {
            Disk d = disk(p);
            problems.expect(d.entries() == 1 && d.sole().state() == EscrowFiles.EXITING,
                    what + ": the saved account should hold E EXITING: " + d.account());
            return d.sole();
        }

        /** The ledger holds exactly one record, the INTENT of {@code entry} (decided, not done). */
        void expectIntentOnly(String p, EscrowFiles.Entry entry, String what) throws IOException {
            List<EscrowFiles.Record> records = EscrowFiles.ledgerOf(rig, p);
            problems.expect(records.size() == 1 && records.get(0).isIntent()
                            && records.get(0).entryHex().equals(entry.idHex()),
                    what + ": the ledger should hold the one INTENT of E: " + records);
        }

        void expectNoRecords(String p, String what) throws IOException {
            problems.expect(EscrowFiles.ledgerOf(rig, p).isEmpty(), what + ": the ledger should hold no record: "
                    + EscrowFiles.ledgerOf(rig, p));
        }

        void expectQuiet(String p, String what) throws IOException {
            problems.expect(EscrowFiles.quarantined(rig, p).isEmpty(), what + ": a valid ledger was quarantined");
            problems.expect(!rig.log().readAll().contains("MTR-0101"), what + ": a valid ledger reported MTR-0101");
        }

        /** A real move-in; checks the step really happened (the stack is in the window and in the account, not the inventory). */
        void moveIn(String p) throws IOException, InterruptedException {
            shiftIn(p);
            Snap snap = inv(p);
            Integer landed = snap.window().size() == 1 ? snap.window().keySet().iterator().next() : null;
            if (landed == null || !"DIAMOND*5".equals(snap.window().get(landed)) || snap.count("DIAMOND") != 0
                    || snap.entries() != 1) {
                throw new IllegalStateException("the move-in did not happen: " + snap);
            }
            landing.put(p, landed);
        }

        /**
         * A real placement of the player's diamonds into window slot {@code slot} (a pickup from hotbar 0, then a click on
         * the slot), for the GUIs whose buttons consume one named slot: a shift-click lets the product choose the slot.
         */
        void placeIn(String p, int slot) throws IOException, InterruptedException {
            click(p, 36, "PICKUP");
            click(p, slot, "PICKUP");
            Snap snap = inv(p);
            if (!"DIAMOND*5".equals(snap.window().get(slot)) || snap.window().size() != 1 || snap.count("DIAMOND") != 0
                    || snap.entries() != 1) {
                throw new IllegalStateException("the placement did not happen: " + snap);
            }
            landing.put(p, slot);
        }

        /** The window slot the player's stack landed in (the product picks it; the harness follows it). */
        int slot(String p) {
            Integer landed = landing.get(p);
            if (landed == null) {
                throw new IllegalStateException(p + " has moved nothing in");
            }
            return landed;
        }

        /** Kills at a production gate, reached by {@code trigger}. */
        void crash(String gate, String trigger) throws IOException, InterruptedException {
            rig.killAtGate(gate, trigger);
            problems.expect(!rig.alive(), "the server survived the kill");
        }

        /** As {@link #crash(String, String)}, with a step taken while a gate that parks the IO thread holds the exit half-way. */
        void crash(String gate, String trigger, CrashRig.WhileParked whileParked)
                throws IOException, InterruptedException {
            rig.killAtGate(gate, trigger, whileParked);
            problems.expect(!rig.alive(), "the server survived the kill");
        }

        /** A kill at a quiescent point: {@code /materione claim} reaches its gate and touches nothing here. */
        void quiet(String p) throws IOException, InterruptedException {
            crash(CLAIM_DONE, "dprobe cmd " + p + " materione claim");
        }

        void reboot() throws IOException, InterruptedException {
            rig.boot();
        }

        Disk disk(String p) throws IOException {
            PlayerDat dat = rig.savedData(p);
            Object raw = dat.bukkitValues().get("materione:gui_escrow");
            return new Disk(dat, raw instanceof byte[] bytes ? bytes : null);
        }

        void appendLedger(String p, byte[]... records) throws IOException {
            EscrowFiles.writeLedger(rig, p, EscrowFiles.concat(EscrowFiles.ledgerHeader(), EscrowFiles.concat(records)));
        }

        boolean awaitInv(String p, Duration timeout, java.util.function.Predicate<Snap> condition) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (condition.test(inv(p))) {
                    return true;
                }
                Thread.sleep(300);
            }
            return condition.test(inv(p));
        }

        // ---- the assertions ---------------------------------------------------------------------------------------

        /** G1 and I6 after a boot: the stack is back exactly once, nothing is owed or held, no other stack moved. */
        void expectReturnedOnce(String p, String what) throws IOException, InterruptedException {
            login(p);
            Snap snap = inv(p);
            problems.expect(snap.count("DIAMOND") == 5, what + ": the diamonds should be in the inventory exactly once: " + snap);
            problems.expect(snap.entries() == 0, what + ": nothing may remain owed or held: " + snap);
            problems.expect(snap.window().isEmpty(), what + ": no window should be open: " + snap);
            problems.expect(snap.count("APPLE") == 3, what + ": the bystander stack changed: " + snap);
            problems.expect(droppedItemsSafe() == 0, what + ": a stack was dropped in the world (G4)");
        }

        /** G2: the stack of a durable decision is gone for good; the bystander is untouched. */
        void expectConsumed(String p, String what) throws IOException, InterruptedException {
            login(p);
            Snap snap = inv(p);
            problems.expect(snap.count("DIAMOND") == 0, what + ": the consumed stack came back (resurrection): " + snap);
            problems.expect(snap.entries() == 0, what + ": the account still holds an entry: " + snap);
            problems.expect(snap.count("APPLE") == 3, what + ": the bystander stack changed: " + snap);
            problems.expect(droppedItemsSafe() == 0, what + ": a stack was dropped in the world (G4)");
        }

        private int droppedItemsSafe() throws IOException, InterruptedException {
            return droppedItems();
        }

        void expectDiskHeld(String p, String what) throws IOException {
            Disk d = disk(p);
            problems.expect(d.count("minecraft:diamond") == 0, what + ": the diamonds are still in the saved inventory: "
                    + d.dat().inventory());
            problems.expect(d.entries() == 1 && d.sole().state() == EscrowFiles.HELD,
                    what + ": the saved account should hold one HELD entry: " + d.account());
        }

        void expectDiskInInventory(String p, String what) throws IOException {
            Disk d = disk(p);
            problems.expect(d.count("minecraft:diamond") == 5, what + ": the saved inventory should hold the diamonds: "
                    + d.dat().inventory());
            problems.expect(d.entries() == 0, what + ": the saved account should hold nothing: " + d.account());
        }

        // ---- the rows: tier 1 -------------------------------------------------------------------------------------

        /**
         * K1: a move-in whose save never happened. The kill is <em>inside</em> the step, at the production gate
         * {@code account-written}: the account has just been written into the player's data and the inventory has not
         * changed yet. The disk still holds S (F3): the stack in the inventory, no account.
         */
        void k1MidStep() throws Exception {
            String p = "K1Bot";
            seed(p);
            crash(ACCOUNT_WRITTEN, "dprobe click " + p + " 36 0 QUICK_MOVE");
            Disk d = disk(p);
            problems.expect(d.dat().inventory().get(0) != null && d.count("minecraft:diamond") == 5,
                    "disk S should still hold the diamonds in the inventory: " + d.dat().inventory());
            problems.expect(d.accountBytes() == null, "disk S has an account: " + d.dat().bukkitValues().keySet());
            reboot();
            expectReturnedOnce(p, "after the boot");
        }

        /** K1, the other side of the step: the handler returned (gate {@code click-applied}), still no save. */
        void k1AfterStep() throws Exception {
            String p = "K1bBot";
            seed(p);
            crash(CLICK_APPLIED, "dprobe click " + p + " 36 0 QUICK_MOVE");
            expectDiskInInventory(p, "disk S");
            reboot();
            expectReturnedOnce(p, "after the boot");
        }

        /** K2: the same move-in, then a save: the snapshot has the stack in the account and not in the inventory. */
        void k2() throws Exception {
            String p = "K2Bot";
            seed(p);
            moveIn(p);
            save(p);
            quiet(p);
            expectDiskHeld(p, "disk S'");
            problems.expect(disk(p).dat().inventory().containsValue("minecraft:apple x3"),
                    "the bystander stack changed on disk: " + disk(p).dat().inventory());
            reboot();
            expectReturnedOnce(p, "after the boot");
        }

        /** K5: a move-out (slot to inventory) with no save since: the older snapshot still holds the stack in the account only. */
        void k5() throws Exception {
            String p = "K5Bot";
            seed(p);
            moveIn(p);
            save(p);
            crash(CLICK_APPLIED, "dprobe click " + p + " " + slot(p) + " 0 QUICK_MOVE");
            expectDiskHeld(p, "disk S'");
            reboot();
            expectReturnedOnce(p, "after the boot");
        }

        /** K6: ...a save then completed: the stack is in the inventory and gone from the account, from one instant. */
        void k6() throws Exception {
            String p = "K6Bot";
            seed(p);
            moveIn(p);
            save(p);
            shiftOut(p);
            problems.expect(inv(p).count("DIAMOND") == 5, "the move-out did not happen");
            save(p);
            quiet(p);
            expectDiskInInventory(p, "disk S''");
            reboot();
            expectReturnedOnce(p, "after the boot (nothing owed, nothing doubled)");
        }

        /**
         * K7: closing the window with a full inventory. The stack stays OWED in the account, never dropped (G4); the kill
         * after the save finds it still owed, and {@code /materione claim} gives it back once there is room.
         */
        void k7() throws Exception {
            String p = "K7Bot";
            seed(p);
            moveIn(p);
            rig.probe("fill", p + " DIRT 64");
            rig.probe("close", p);
            Snap closed = inv(p);
            problems.expect(closed.entries() == 1 && closed.account().entries().get(0).state() == EscrowFiles.OWED,
                    "the close should leave one OWED entry: " + closed);
            problems.expect(droppedItems() == 0, "the overflow was dropped in the world before the kill (G4)");
            save(p);
            quiet(p);
            Disk d = disk(p);
            problems.expect(d.count("minecraft:diamond") == 0, "diamonds in the saved (full) inventory");
            problems.expect(d.entries() == 1 && d.sole().state() == EscrowFiles.OWED,
                    "the saved account should hold one OWED entry: " + d.account());
            reboot();
            login(p);
            Snap booted = inv(p);
            problems.expect(booted.count("DIAMOND") == 0 && booted.entries() == 1,
                    "with the inventory still full the stack must stay owed: " + booted);
            problems.expect(rig.log().readAll().contains("MTR-0103"), "recovery did not report MTR-0103 for the owed stack");
            problems.expect(droppedItems() == 0, "the owed stack was dropped in the world (G4)");
            set(p, 0, "DIRT", 0);
            rig.probe("cmd", p + " materione claim");
            boolean claimed = awaitInv(p, Duration.ofSeconds(15), s -> s.count("DIAMOND") == 5 && s.entries() == 0);
            problems.expect(claimed, "/materione claim did not return the owed stack: " + inv(p));
            problems.expect(droppedItems() == 0, "the claim dropped a stack in the world (G4)");
        }

        /** K8a: a kill in the middle of a quit, after the close released the window and before the save. As K5. */
        void k8a() throws Exception {
            String p = "K8aBot";
            seed(p);
            moveIn(p);
            save(p);
            crash(RELEASE_DONE, "dprobe leave " + p);
            expectDiskHeld(p, "disk S' (the quit never saved)");
            reboot();
            expectReturnedOnce(p, "after the boot");
        }

        /** K8b: a quit that completed: the close ran first (F4), so the save holds the stack in the inventory, none HELD. */
        void k8b() throws Exception {
            String p = "K8bBot";
            seed(p);
            moveIn(p);
            rig.probe("leave", p);
            rig.kill();
            expectDiskInInventory(p, "the quit's save");
            reboot();
            expectReturnedOnce(p, "after the boot");
        }

        /**
         * K8c: a graceful server stop with a window open. Whatever the stop's save held, it holds the stack exactly once
         * (in the inventory or in the account), never both and never neither.
         */
        void k8c() throws Exception {
            String p = "K8cBot";
            seed(p);
            moveIn(p);
            int exit = rig.stop();
            problems.expect(exit == 0, "the stop exited " + exit);
            Disk d = disk(p);
            int inInventory = d.count("minecraft:diamond");
            boolean exactlyOnce = inInventory == 5 && d.entries() == 0 || inInventory == 0 && d.entries() == 1;
            problems.expect(exactlyOnce, "the stop's save holds the stack zero or two times: inventory "
                    + d.dat().inventory() + ", account " + d.account());
            reboot();
            expectReturnedOnce(p, "after the boot");
        }

        // ---- the rows: death and disable --------------------------------------------------------------------------

        void keepInventory(boolean keep) throws IOException, InterruptedException {
            rig.command("gamerule keepInventory " + keep, "keepInventory");
        }

        /** K9a: killed inside {@code PlayerDeathEvent} (the release ran, the drops are not fixed yet): the death never persisted. */
        void k9a() throws Exception {
            deathInTheEvent("K9aBot", false);
        }

        void k9b() throws Exception {
            deathInTheEvent("K9bBot", true);
        }

        private void deathInTheEvent(String p, boolean keep) throws Exception {
            keepInventory(keep);
            seed(p);
            moveIn(p);
            save(p);
            crash(DEATH_RELEASED, "dprobe die " + p);
            expectDiskHeld(p, "disk S' (the death was never saved)");
            reboot();
            expectReturnedOnce(p, "after the boot");
        }

        /**
         * K9c: a death that completed with {@code keepInventory} off: the stack was released into the drops (the world's
         * store, outside the guarantee once dropped) and the account no longer holds it: nothing is owed, nothing is
         * duplicated into the respawned player.
         */
        void k9c() throws Exception {
            String p = "K9cBot";
            keepInventory(false);
            seed(p);
            moveIn(p);
            save(p);
            rig.probe("die", p);
            String items = rig.probe("items", "");
            problems.expect(items.contains("DIAMOND*5"), "the window's diamonds were not among the death drops: " + items);
            rig.probe("respawn", p);
            save(p);
            quiet(p);
            Disk d = disk(p);
            problems.expect(d.count("minecraft:diamond") == 0, "the respawned player's save holds the diamonds: " + d.dat().inventory());
            problems.expect(d.entries() == 0, "the respawned player's account still holds an entry: " + d.account());
            reboot();
            login(p);
            Snap snap = inv(p);
            problems.expect(snap.count("DIAMOND") == 0 && snap.entries() == 0,
                    "after the boot nothing may be returned for a stack that was dropped: " + snap);
        }

        /** K9d: a death that completed with {@code keepInventory} on: the stack is kept, once. */
        void k9d() throws Exception {
            String p = "K9dBot";
            keepInventory(true);
            seed(p);
            moveIn(p);
            save(p);
            rig.probe("die", p);
            problems.expect(droppedItems() == 0, "a stack was dropped although keepInventory is on");
            rig.probe("respawn", p);
            save(p);
            quiet(p);
            expectDiskInInventory(p, "the respawned player's save");
            reboot();
            expectReturnedOnce(p, "after the boot");
            keepInventory(false);
        }

        /** K20a: killed inside the plugin's disable, in the release it runs before the server saves: as K5. */
        void k20a() throws Exception {
            String p = "K20aBot";
            seed(p);
            moveIn(p);
            save(p);
            crash(RELEASE_DONE, "dprobe disable");
            expectDiskHeld(p, "disk S'");
            reboot();
            expectReturnedOnce(p, "after the boot");
        }

        /** K20b: the plugin disabled and the server saved afterwards: the snapshot holds the stack in the inventory, none HELD. */
        void k20b() throws Exception {
            String p = "K20bBot";
            seed(p);
            moveIn(p);
            rig.probe("disable", "");
            save(p);
            rig.kill();
            expectDiskInInventory(p, "the save after the disable");
            reboot();
            expectReturnedOnce(p, "after the boot");
        }

        // ---- the rows: tier 2, the real consume exit (#248) ------------------------------------------------------------

        /**
         * K10a: a kill right after step 1 (the entry is frozen EXITING in the live account) on the owner thread, with the
         * INTENT not even queued. The disk is S' (E HELD): there is no decision, so E comes back.
         */
        void k10a() throws Exception {
            String p = "K10aBot";
            heldAndSaved(p);
            crash(EXIT_BEGUN, press(p, PLAIN_BUTTON));
            expectDiskHeld(p, "disk S'");
            expectNoRecords(p, "nothing was queued");
            reboot();
            expectReturnedOnce(p, "no INTENT, no decision");
        }

        /**
         * K10b: the ledger writer is stopped just before it writes the INTENT (its IO thread is parked; the server still
         * ticks), a save lands with E EXITING, and the kill follows: S' holds a frozen entry and the ledger holds nothing.
         */
        void k10b() throws Exception {
            String p = "K10bBot";
            heldAndSaved(p);
            crash(LEDGER_BEFORE_APPEND, press(p, PLAIN_BUTTON), () -> save(p));
            exiting(p, "disk S'");
            expectNoRecords(p, "before the append");
            reboot();
            expectReturnedOnce(p, "a frozen entry with no INTENT is returned");
        }

        /**
         * K10c (file state): the kill landed <em>inside</em> the append, so the ledger ends in a partial INTENT. A kill
         * inside a write cannot be gated without altering the write, so the bytes are the real INTENT of the real exit
         * (its txn and entry id, read from the saved account), cut short and appended after the kill: a torn tail is not
         * a record (section 5.3), E comes back, and a read leaves the tail alone for the next writer to cut.
         */
        void k10c() throws Exception {
            String p = "K10cBot";
            heldAndSaved(p);
            crash(LEDGER_BEFORE_APPEND, press(p, PLAIN_BUTTON), () -> save(p));
            EscrowFiles.Entry e = exiting(p, "disk S'");
            byte[] intent = EscrowFiles.intent(e.txn(), e.id(), System.currentTimeMillis());
            appendLedger(p, EscrowFiles.prefix(intent, 30));
            long before = Files.size(EscrowFiles.ledgerFile(rig, p));
            reboot();
            expectReturnedOnce(p, "a torn INTENT is no decision");
            problems.expect(Files.size(EscrowFiles.ledgerFile(rig, p)) == before,
                    "a read must not rewrite a torn ledger (the next writer truncates)");
            problems.expect(EscrowFiles.quarantined(rig, p).isEmpty(), "a torn tail is not corruption");
        }

        /**
         * K11: the INTENT is durable and the entry is EXITING in the snapshot (the writer's IO thread parks right after
         * the append, so step 3 has not run and a save lands first): recovery drops E, G2, however old the snapshot.
         */
        void k11() throws Exception {
            String p = "K11Bot";
            heldAndSaved(p);
            crash(LEDGER_AFTER_APPEND, press(p, PLAIN_BUTTON), () -> save(p));
            EscrowFiles.Entry e = exiting(p, "disk S'");
            problems.expect(disk(p).count("minecraft:diamond") == 0, "the saved inventory still holds the diamonds");
            expectIntentOnly(p, e, "the decision is durable");
            reboot();
            expectConsumed(p, "a durable INTENT");
            expectQuiet(p, "a valid ledger");
        }

        /** K11 with a snapshot that predates E: the stack is still in the inventory; the INTENT finds no entry (G3). */
        void k11Rollback() throws Exception {
            String p = "K11rBot";
            seed(p, CONSUME_GUI);
            placeIn(p, 0); // never saved: the disk is S, with the stack in the inventory
            crash(LEDGER_AFTER_APPEND, press(p, PLAIN_BUTTON));
            expectDiskInInventory(p, "disk S");
            problems.expect(EscrowFiles.ledgerOf(rig, p).size() == 1, "the decision should be durable: "
                    + EscrowFiles.ledgerOf(rig, p));
            reboot();
            expectReturnedOnce(p, "an INTENT for an entry the snapshot never held (a rollback, not a duplicate)");
        }

        /** K12: step 3 ran (the owner thread parks right after it), no save since: S' holds E HELD, the INTENT is durable. */
        void k12() throws Exception {
            String p = "K12Bot";
            heldAndSaved(p);
            EscrowFiles.Entry e = disk(p).sole();
            crash(EXIT_APPLIED, press(p, PLAIN_BUTTON));
            expectDiskHeld(p, "disk S'");
            expectIntentOnly(p, e, "the decision is durable");
            reboot();
            expectConsumed(p, "step 3 ran, no save since");
        }

        /** K13: the consume completed on its own and a save followed: S'' has neither the stack nor E, the INTENT is inert. */
        void k13() throws Exception {
            String p = "K13Bot";
            heldAndSaved(p);
            EscrowFiles.Entry e = disk(p).sole();
            pressNow(p, PLAIN_BUTTON);
            boolean done = awaitInv(p, Duration.ofSeconds(20), s -> s.entries() == 0 && s.window().isEmpty());
            problems.expect(done, "the consume did not complete: " + inv(p));
            save(p);
            quiet(p);
            Disk d = disk(p);
            problems.expect(d.count("minecraft:diamond") == 0 && d.entries() == 0,
                    "disk S'' should hold neither: " + d.dat().inventory() + " " + d.account());
            expectIntentOnly(p, e, "an inert record");
            reboot();
            expectConsumed(p, "a record whose entry is already gone");
            expectQuiet(p, "an inert record");
        }

        /**
         * K14a: the player quits after the INTENT is written and before step 3. E is EXITING, so the close does not
         * return it (no second copy) and the quit's save holds it EXITING; recovery sees the INTENT and drops it.
         */
        void k14a() throws Exception {
            String p = "K14aBot";
            heldAndSaved(p);
            crash(LEDGER_AFTER_APPEND, press(p, PLAIN_BUTTON), () -> rig.probe("leave", p));
            EscrowFiles.Entry e = exiting(p, "the quit's save");
            problems.expect(disk(p).count("minecraft:diamond") == 0, "the close returned a frozen entry's stack: "
                    + disk(p).dat().inventory());
            expectIntentOnly(p, e, "the decision is durable");
            reboot();
            expectConsumed(p, "K14a (INTENT before the quit)");
        }

        /** K14b: the player quits before the INTENT lands (and it never does): the frozen entry is returned once. */
        void k14b() throws Exception {
            String p = "K14bBot";
            heldAndSaved(p);
            crash(LEDGER_BEFORE_APPEND, press(p, PLAIN_BUTTON), () -> rig.probe("leave", p));
            exiting(p, "the quit's save");
            problems.expect(disk(p).count("minecraft:diamond") == 0, "the close returned a frozen entry's stack");
            expectNoRecords(p, "the INTENT never landed");
            reboot();
            expectReturnedOnce(p, "K14b (no INTENT ever)");
        }

        /**
         * K15: recovery itself killed, twice, at both production gates (before it commits, and after it opened the gate):
         * recovery only changes live memory, so the disk is unchanged and the next boot recovers the same. K16: a
         * recovery that was saved, then a second crash: a drained account returns nothing more.
         */
        void k15() throws Exception {
            String p = "K15Bot";
            seed(p);
            moveIn(p);
            save(p);
            quiet(p);
            byte[] before = disk(p).accountBytes();
            reboot();
            crash(RECOVERY_BEFORE_COMMIT, "dprobe login " + p);
            problems.expect(java.util.Arrays.equals(before, disk(p).accountBytes()), "a kill before the commit changed the disk");
            expectDiskHeld(p, "disk after a recovery killed before its commit");
            reboot();
            crash(RECOVERY_DONE, "dprobe login " + p);
            problems.expect(java.util.Arrays.equals(before, disk(p).accountBytes()), "a kill after the commit (no save) changed the disk");
            expectDiskHeld(p, "disk after a recovery killed after its commit");
            reboot();
            expectReturnedOnce(p, "the third recovery (the same result as the first would have been)");
            save(p);
            quiet(p);
            expectDiskInInventory(p, "K16: the saved recovery");
            reboot();
            expectReturnedOnce(p, "K16: a second crash after a saved recovery");
        }

        /**
         * K17 (file state): a valid INTENT and then a torn record, the partial DONE a kill inside the next append would
         * leave (cut from a real DONE of the same transaction): the INTENT counts, the tail is ignored and left for the
         * next writer.
         */
        void k17() throws Exception {
            String p = "K17Bot";
            heldAndSaved(p);
            crash(LEDGER_AFTER_APPEND, press(p, PLAIN_BUTTON), () -> save(p));
            EscrowFiles.Entry e = exiting(p, "disk S'");
            expectIntentOnly(p, e, "the decision is durable");
            Files.write(EscrowFiles.ledgerFile(rig, p), EscrowFiles.prefix(EscrowFiles.done(e.txn()), 11),
                    java.nio.file.StandardOpenOption.APPEND);
            long before = Files.size(EscrowFiles.ledgerFile(rig, p));
            reboot();
            expectConsumed(p, "an INTENT followed by a torn tail");
            problems.expect(Files.size(EscrowFiles.ledgerFile(rig, p)) == before, "a read rewrote the ledger");
            problems.expect(EscrowFiles.quarantined(rig, p).isEmpty(), "a torn tail was quarantined as corruption");
            problems.expect(!rig.log().readAll().contains("MTR-0101"), "a torn tail reported MTR-0101");
        }

        /**
         * KT: the trash slot is a composition. The player shift-clicks a stack into the trash GUI; the flow (the built-in
         * recipe {@code trash_slot}, expanded) waits a tick and runs {@code custody.consume}; nothing here is trash-specific
         * engine code. The consume asks for no save (D8: nothing waits on it), is durable through a kill, and the stack
         * does not come back.
         */
        void kTrash() throws Exception {
            String p = "KTBot";
            seed(p, TRASH_GUI);
            long saved = Files.getLastModifiedTime(rig.playerDataFile(p)).toMillis();
            shiftIn(p);
            boolean gone = awaitInv(p, Duration.ofSeconds(20),
                    s -> s.count("DIAMOND") == 0 && s.entries() == 0 && s.window().isEmpty());
            problems.expect(gone, "the trash did not consume the stack: " + inv(p));
            problems.expect(rig.log().awaitLine("MATERIONE-GUITRASH-DONE", Duration.ofSeconds(10)) != null,
                    "the trash flow never reached its end");
            problems.expect(Files.getLastModifiedTime(rig.playerDataFile(p)).toMillis() == saved,
                    "a consume with no effect asked for a save (D8)");
            problems.expect(EscrowFiles.ledgerOf(rig, p).size() == 1 && EscrowFiles.ledgerOf(rig, p).get(0).isIntent(),
                    "the trash's decision should be one INTENT: " + EscrowFiles.ledgerOf(rig, p));
            save(p);
            quiet(p);
            Disk d = disk(p);
            problems.expect(d.count("minecraft:diamond") == 0 && d.entries() == 0, "the snapshot after the trash holds the "
                    + "stack: " + d.dat().inventory() + " " + d.account());
            reboot();
            expectConsumed(p, "the trashed stack stays trashed");
        }

        // ---- the rows: the barrier (K23) -------------------------------------------------------------------------------

        private int count(String text, String marker) {
            int found = 0;
            for (int at = text.indexOf(marker); at >= 0; at = text.indexOf(marker, at + 1)) {
                found++;
            }
            return found;
        }

        /** Makes the platform's save of {@code p} unable to land: the file it renames onto is a non-empty directory. */
        Path blockSaves(String p) throws IOException {
            Path old = rig.playerDataFile(p).resolveSibling(rig.playerDataFile(p).getFileName() + "_old");
            deleteTree(old);
            Files.createDirectories(old.resolve("blocker"));
            Files.writeString(old.resolve("blocker").resolve("keep"), "this directory stops the server's player save");
            return old;
        }

        void deleteTree(Path path) throws IOException {
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    for (Path file : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.delete(file);
                    }
                }
            } else {
                Files.deleteIfExists(path);
            }
        }

        /** The effect never ran: its marker is in no line of the log. */
        void expectNoEffect(String what) throws IOException {
            problems.expect(!rig.log().readAll().contains(PAID_MARKER), what + ": the effect ran");
        }

        /**
         * K23a: the barrier's {@code Player.saveData()} returned and the thread stops there, before the read-back that
         * would acknowledge it. The disk holds the snapshot the barrier's own save wrote (the consume already applied), the
         * ledger holds the INTENT and no DONE, and the effect has not run. Recovery applies the INTENT: nothing returns.
         */
        void k23a() throws Exception {
            String p = "K23aBot";
            heldAndSaved(p);
            EscrowFiles.Entry e = disk(p).sole();
            crash(BARRIER_SAVED, press(p, PAID_BUTTON));
            Disk d = disk(p);
            problems.expect(d.count("minecraft:diamond") == 0 && d.entries() == 0,
                    "the barrier's save should hold the consume applied: " + d.dat().inventory() + " " + d.account());
            expectIntentOnly(p, e, "decided, not done");
            expectNoEffect("killed before the read-back");
            reboot();
            expectConsumed(p, "recovery applies the INTENT");
            expectNoEffect("after the boot");
        }

        /**
         * K23b: the read-back finds an older snapshot. The server's player save cannot land (the file it renames onto is a
         * non-empty directory), so after the barrier's save the file on disk is still S', whose {@code gen} predates the
         * exit: "not yet", never "assume". The effect has not run when the kill comes, and never does.
         */
        void k23b() throws Exception {
            String p = "K23bBot";
            heldAndSaved(p);
            EscrowFiles.Entry e = disk(p).sole();
            Path blocker = blockSaves(p);
            pressNow(p, PAID_BUTTON);
            problems.expect(rig.log().awaitLine("did not confirm a snapshot with the exit yet", Duration.ofSeconds(60)) != null,
                    "the barrier never reported an older read-back");
            Snap live = inv(p);
            problems.expect(live.entries() == 0 && live.window().isEmpty(), "the consume itself should be applied: " + live);
            expectNoEffect("while the disk is stuck");
            expectDiskHeld(p, "the disk is still S' (older gen)");
            expectIntentOnly(p, e, "decided, not done");
            rig.kill();
            deleteTree(blocker);
            reboot();
            expectConsumed(p, "G2 through an old snapshot");
            expectNoEffect("the effect never ran, before or after the kill");
        }

        /**
         * K23c: the healthy path. The effect's marker appears only after the file on disk holds the consume (read at the
         * moment the marker appears), exactly once, and the DONE record follows.
         */
        void k23c() throws Exception {
            String p = "K23cBot";
            heldAndSaved(p);
            EscrowFiles.Entry e = disk(p).sole();
            pressNow(p, PAID_BUTTON);
            problems.expect(rig.log().awaitLine(PAID_MARKER, Duration.ofSeconds(60)) != null, "the effect never ran");
            Disk d = disk(p);
            problems.expect(d.count("minecraft:diamond") == 0 && d.entries() == 0,
                    "the effect ran before the file held the consume: " + d.dat().inventory() + " " + d.account());
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && EscrowFiles.ledgerOf(rig, p).size() < 2) {
                Thread.sleep(200);
            }
            List<EscrowFiles.Record> records = EscrowFiles.ledgerOf(rig, p);
            problems.expect(records.size() == 2 && records.get(0).isIntent() && records.get(1).isDone()
                            && records.get(1).txnHex().equals(records.get(0).txnHex())
                            && records.get(0).entryHex().equals(e.idHex()),
                    "the ledger should hold INTENT then DONE of one exit: " + records);
            problems.expect(count(rig.log().readAll(), PAID_MARKER) == 1, "the effect ran "
                    + count(rig.log().readAll(), PAID_MARKER) + " times");
            rig.kill();
            reboot();
            expectConsumed(p, "after the boot");
            expectNoEffect("the effect is not repeated by recovery");
        }

        /**
         * K23d: the disk recovers. While the save cannot land the effect waits (the barrier retries with a growing
         * delay); once the file can be replaced again the next save lands, the read-back proves it, and the effect runs
         * once, late.
         */
        void k23d() throws Exception {
            String p = "K23dBot";
            heldAndSaved(p);
            Path blocker = blockSaves(p);
            pressNow(p, PAID_BUTTON);
            problems.expect(rig.log().awaitLine("did not confirm a snapshot with the exit yet", Duration.ofSeconds(60)) != null,
                    "the barrier never reported an older read-back");
            expectNoEffect("while the disk is stuck");
            deleteTree(blocker);
            problems.expect(rig.log().awaitLine(PAID_MARKER, Duration.ofSeconds(90)) != null,
                    "the effect never ran after the disk recovered");
            Disk d = disk(p);
            problems.expect(d.count("minecraft:diamond") == 0 && d.entries() == 0,
                    "the effect ran before the file held the consume: " + d.dat().inventory() + " " + d.account());
            problems.expect(count(rig.log().readAll(), PAID_MARKER) == 1, "the effect ran "
                    + count(rig.log().readAll(), PAID_MARKER) + " times");
            rig.kill();
            reboot();
            expectConsumed(p, "after the boot");
            expectNoEffect("the effect is not repeated by recovery");
        }

        /**
         * K18: fail closed. Three players, three unreadable states: a ledger corrupt before its end (quarantined), an
         * account with a bad CRC, an account of a newer version. Each is told no, each strict click is refused, and
         * <em>nothing is returned or deleted</em>: the account value on disk is byte-identical after the boot and a save.
         */
        void k18() throws Exception {
            String ledgerBad = "K18aBot";
            String crcBad = "K18bBot";
            String newer = "K18cBot";
            heldAndSaved(ledgerBad);
            EscrowFiles.Entry e = disk(ledgerBad).sole();
            seed(crcBad);
            moveIn(crcBad);
            byte[] bad = EscrowFiles.encodeAccount(inv(crcBad).account());
            bad[bad.length - 1] ^= 0x01; // the stored CRC no longer matches
            rig.probe("putbytes", crcBad + " gui_escrow " + EscrowFiles.hex(bad));
            save(crcBad);
            seed(newer);
            moveIn(newer);
            byte[] future = EscrowFiles.encodeAccount(inv(newer).account());
            future[1] = 2; // a version this build does not know
            rig.probe("putbytes", newer + " gui_escrow " + EscrowFiles.hex(future));
            save(newer);
            quiet(newer);
            byte[] intent = EscrowFiles.intent(EscrowFiles.txn(1), e.id(), System.currentTimeMillis());
            appendLedger(ledgerBad, EscrowFiles.withBadCrc(intent), EscrowFiles.done(EscrowFiles.txn(1)));
            Map<String, byte[]> savedAccounts = new LinkedHashMap<>();
            for (String p : List.of(ledgerBad, crcBad, newer)) {
                savedAccounts.put(p, disk(p).accountBytes());
            }
            reboot();
            for (String p : List.of(ledgerBad, crcBad, newer)) {
                login(p);
                open(p);
                set(p, 0, "EMERALD", 2);
                shiftIn(p);
                Snap snap = inv(p);
                problems.expect("EMERALD*2".equals(snap.slots().get(0)) && snap.window().isEmpty(),
                        p + ": a strict move-in must be refused for an unrecoverable player: " + snap);
                problems.expect(snap.count("DIAMOND") == 0, p + ": recovery returned a stack from an unreadable state: " + snap);
                save(p);
                problems.expect(java.util.Arrays.equals(savedAccounts.get(p), disk(p).accountBytes()),
                        p + ": the account value on disk changed (it must be preserved untouched)");
            }
            problems.expect(EscrowFiles.quarantined(rig, ledgerBad).size() == 1,
                    "the corrupt ledger should be renamed to <uuid>.ledger.corrupt-<ms>, once");
            problems.expect(!Files.exists(EscrowFiles.ledgerFile(rig, ledgerBad)),
                    "the corrupt ledger was left in place beside its quarantine copy");
            problems.expect(rig.log().readAll().contains("MTR-0101"), "no MTR-0101 was reported");
            problems.expect(droppedItems() == 0, "a stack was dropped in the world (G4)");
        }

        // ---- the rows: the cursor and the platform's own save -----------------------------------------------------

        /** K21a: a pickup (slot to cursor), then a save: the stack is in no save. The one row whose answer is loss (G5). */
        void k21a() throws Exception {
            String p = "K21aBot";
            seed(p);
            moveIn(p);
            save(p);
            click(p, slot(p), "PICKUP");
            problems.expect("DIAMOND*5".equals(inv(p).cursor()), "the pickup did not put the stack on the cursor: " + inv(p));
            save(p);
            quiet(p);
            Disk d = disk(p);
            problems.expect(d.count("minecraft:diamond") == 0 && d.entries() == 0,
                    "the cursor stack must be in no part of the save: " + d.dat().inventory() + " " + d.account());
            problems.expect(!d.dat().root().containsKey("CarriedItem") && !d.dat().root().containsKey("Cursor"),
                    "a cursor key exists: " + d.dat().root().keySet());
            reboot();
            login(p);
            Snap snap = inv(p);
            problems.expect(snap.count("DIAMOND") == 0 && snap.entries() == 0,
                    "G5: the stack on the cursor at the save is lost, as in vanilla: " + snap);
            problems.expect(snap.count("APPLE") == 3, "the bystander stack changed: " + snap);
        }

        /** K21b: the same pickup with no save of it: the older snapshot still has the stack in the account, so it comes back. */
        void k21b() throws Exception {
            String p = "K21bBot";
            seed(p);
            moveIn(p);
            save(p);
            crash(CLICK_APPLIED, "dprobe click " + p + " " + slot(p) + " 0 PICKUP");
            expectDiskHeld(p, "disk S'");
            reboot();
            expectReturnedOnce(p, "after the boot (rollback, as K5)");
        }

        /** K22a: a stack on the cursor (lifted from the inventory) at a save is not in the save: vanilla's cursor exposure. */
        void k22a() throws Exception {
            String p = "K22aBot";
            seed(p);
            click(p, 36, "PICKUP");
            problems.expect("DIAMOND*5".equals(inv(p).cursor()), "the click did not lift the stack: " + inv(p));
            save(p);
            quiet(p);
            Disk d = disk(p);
            problems.expect(d.count("minecraft:diamond") == 0 && d.entries() == 0,
                    "a cursor stack is in no save: " + d.dat().inventory() + " " + d.account());
            reboot();
            login(p);
            Snap snap = inv(p);
            problems.expect(snap.count("DIAMOND") == 0 && snap.entries() == 0, "G5: lost, as in vanilla: " + snap);
        }

        /** K22b: the placement (cursor to slot) creates the entry atomically with the cursor reduction; a save after it returns it once. */
        void k22b() throws Exception {
            String p = "K22bBot";
            seed(p);
            click(p, 36, "PICKUP");
            click(p, 0, "PICKUP");
            Snap placed = inv(p);
            problems.expect("DIAMOND*5".equals(placed.window().get(0)) && placed.cursor().equals("empty")
                            && placed.entries() == 1,
                    "the placement did not create the entry: " + placed);
            save(p);
            quiet(p);
            expectDiskHeld(p, "disk S'");
            reboot();
            expectReturnedOnce(p, "after the boot");
        }

        /** K3 (file state): a kill during the platform's write leaves the old {@code .dat} whole and a stray partial temp file. */
        void k3() throws Exception {
            String p = "K3Bot";
            seed(p);
            moveIn(p);
            quiet(p);
            Path dat = rig.playerDataFile(p);
            byte[] whole = Files.readAllBytes(dat);
            Path temp = dat.resolveSibling(CrashRig.offlineUuid(p) + "-8675309.dat");
            Files.write(temp, java.util.Arrays.copyOf(whole, whole.length / 2)); // half of a write
            expectDiskInInventory(p, "the old .dat is intact");
            reboot();
            expectReturnedOnce(p, "after the boot (the stray temp is never read)");
        }

        /**
         * K4 (file state): a kill between {@code safeReplaceFile}'s two moves leaves {@code .dat_old} = the previous
         * snapshot and no {@code .dat}; the boot loads the previous complete snapshot (here S, with the stack in the
         * inventory) and recovery is a no-op.
         */
        void k4() throws Exception {
            String p = "K4Bot";
            seed(p);
            moveIn(p);
            save(p);
            quiet(p);
            Files.delete(rig.playerDataFile(p));
            Path old = rig.playerDataFile(p).resolveSibling(rig.playerDataFile(p).getFileName() + "_old");
            problems.expect(Files.exists(old), "no .dat_old next to the deleted .dat");
            PlayerDat previous = PlayerDat.read(old);
            problems.expect(previous.inventory().containsValue("minecraft:diamond x5"),
                    ".dat_old should be the previous complete snapshot S: " + previous.inventory());
            reboot();
            expectReturnedOnce(p, "after the boot (loaded from .dat_old)");
        }

        // ---- K19 --------------------------------------------------------------------------------------------------

        /**
         * K19: the old design's hole, where an autosave lands between a click and its deferred mirror. There is no
         * deferred mirror, so with the Paper autosave on <em>every tick</em> and a click every half second, every
         * snapshot ever read is exactly the state before the click or exactly the state after it: the stack is in the
         * inventory or in the account, never both and never neither.
         */
        void k19() throws Exception {
            String p = "K19Bot";
            seed(p);
            Path dat = rig.playerDataFile(p);
            int snapshots = 0;
            for (int i = 0; i < 10; i++) {
                boolean in = i % 2 == 0;
                if (in) {
                    moveIn(p);
                } else {
                    shiftOut(p);
                }
                long until = System.nanoTime() + Duration.ofMillis(700).toNanos();
                while (System.nanoTime() < until) {
                    try {
                        PlayerDat read = PlayerDat.read(dat);
                        Object raw = read.bukkitValues().get("materione:gui_escrow");
                        int entries = raw instanceof byte[] bytes ? EscrowFiles.parseAccount(bytes).entries().size() : 0;
                        int diamonds = 0;
                        for (String stack : read.inventory().values()) {
                            if (stack.startsWith("minecraft:diamond x")) {
                                diamonds += Integer.parseInt(stack.substring(stack.indexOf(" x") + 2));
                            }
                        }
                        snapshots++;
                        boolean inInventory = diamonds == 5 && entries == 0;
                        boolean inAccount = diamonds == 0 && entries == 1;
                        problems.expect(inInventory || inAccount, "click " + i + ": a torn snapshot, inventory diamonds="
                                + diamonds + " account entries=" + entries);
                    } catch (IOException | RuntimeException replacing) {
                        // the file was being replaced as it was opened: not a snapshot
                    }
                    Thread.sleep(15);
                }
            }
            problems.expect(snapshots > 100, "too few snapshots were read to mean anything: " + snapshots);
            // the last state, killed and recovered once
            quiet(p);
            reboot();
            expectReturnedOnce(p, "after the boot");
        }
    }
}
