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
 * ({@code destroyForcibly} on the whole process tree). K23 (the barrier) waits for row B (#311).
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
 * end to end. Tier 2 (the consume exit, K10 to K14, K17) has no caller before #248, so the harness <em>crafts the
 * two things the missing caller would have left behind</em>: an EXITING entry (the real HELD entry of a real click, its
 * state byte flipped in the live data, then a real save) and an INTENT record (appended to the ledger file between
 * the kill and the boot, by {@link EscrowFiles}, an independent implementation of the layout); recovery, the
 * preload, the gate and the refusal are then the product's. K3 and K4 (a kill inside the platform's own save) are file
 * states: the gate cannot sit inside {@code PlayerDataStorage.save}.
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

    /** The default run: the rows that carry the most per boot. K1 (mid-step), K2, K5, K11/K12 (G2) and K18 (fail closed). */
    public HarnessResult core(HarnessOptions options) {
        Map<String, Row> rows = new LinkedHashMap<>();
        rows.put("K1 kill inside a move-in step", Session::k1MidStep);
        rows.put("K2 move-in then a save", Session::k2);
        rows.put("K5 move-out, no save since", Session::k5);
        rows.put("K11/K12 INTENT durable, E EXITING on disk", Session::k11);
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

    /** Opt-in: the ledger and recovery rows (K10, K11 rolled back, K13, K14, K15, K16, K17). */
    public HarnessResult ledger(HarnessOptions options) {
        Map<String, Row> rows = new LinkedHashMap<>();
        rows.put("K10 INTENT torn mid-append", Session::k10);
        rows.put("K11 INTENT durable, snapshot predates E (G3)", Session::k11Rollback);
        rows.put("K13 INTENT and a snapshot already without E", Session::k13);
        rows.put("K14 quit before the ack, INTENT lands after the save", Session::k14);
        rows.put("K15/K16 recovery killed, then a second crash after a saved recovery", Session::k15);
        rows.put("K17 INTENT then a torn tail", Session::k17);
        return run(options, "escrow-crash-ledger", null, rows);
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
        Problems problems = new Problems();
        CrashRig rig = null;
        try {
            rig = CrashRig.createForEscrow(options, scenario, paperYaml, PACKS);
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
            rig.command("materione gui open guilogic:escrow " + p, "player=" + p + " container=");
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
            login(p);
            set(p, 0, "DIAMOND", 5);
            set(p, 1, "APPLE", 3);
            open(p);
            save(p);
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

        /** The live account's first entry, read from the player's data. */
        EscrowFiles.Entry liveEntry(String p) throws IOException, InterruptedException {
            Snap snap = inv(p);
            if (snap.entries() != 1) {
                throw new IllegalStateException("expected one live entry: " + snap);
            }
            return snap.account().entries().get(0);
        }

        /** Crafts a tier-2 freeze the missing caller would have made: the live entry becomes EXITING under {@code txn}. */
        EscrowFiles.Entry freeze(String p, int txn) throws IOException, InterruptedException {
            EscrowFiles.Account account = inv(p).account();
            EscrowFiles.Entry frozen = account.entries().get(0).exiting(EscrowFiles.txn(txn));
            EscrowFiles.Account next = new EscrowFiles.Account(account.gen() + 1, List.of(frozen));
            rig.probe("adopt", p + " " + EscrowFiles.hex(EscrowFiles.encodeAccount(next)));
            return frozen;
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

        // ---- the rows: tier 2 and the ledger (crafted records, real recovery) ------------------------------------------

        /** The shared start of the exit rows: S' on disk holds E HELD; {@code frozen} also freezes it in the live data first. */
        private EscrowFiles.Entry heldAndSaved(String p, boolean frozen) throws Exception {
            seed(p);
            moveIn(p);
            EscrowFiles.Entry entry = frozen ? freeze(p, 1) : liveEntry(p);
            save(p);
            return entry;
        }

        /** K10: the INTENT's append was cut by the kill: not a record (section 5.3), so E comes back. */
        void k10() throws Exception {
            String p = "K10Bot";
            EscrowFiles.Entry e = heldAndSaved(p, true);
            quiet(p);
            byte[] intent = EscrowFiles.intent(EscrowFiles.txn(1), e.id(), System.currentTimeMillis());
            appendLedger(p, EscrowFiles.prefix(intent, 30));
            long before = Files.size(EscrowFiles.ledgerFile(rig, p));
            Disk d = disk(p);
            problems.expect(d.entries() == 1 && d.sole().state() == EscrowFiles.EXITING,
                    "disk S' should hold E EXITING: " + d.account());
            reboot();
            expectReturnedOnce(p, "a torn INTENT is no decision");
            problems.expect(Files.size(EscrowFiles.ledgerFile(rig, p)) == before,
                    "a read must not rewrite a torn ledger (the next writer truncates)");
            problems.expect(EscrowFiles.quarantined(rig, p).isEmpty(), "a torn tail is not corruption");
        }

        /** K11/K12: the INTENT is durable and E is EXITING in the snapshot (step 3 ran or not): recovery drops it, G2. */
        void k11() throws Exception {
            String p = "K11Bot";
            EscrowFiles.Entry e = heldAndSaved(p, true);
            quiet(p);
            appendLedger(p, EscrowFiles.intent(EscrowFiles.txn(1), e.id(), System.currentTimeMillis()));
            Disk d = disk(p);
            problems.expect(d.entries() == 1 && d.sole().state() == EscrowFiles.EXITING && d.count("minecraft:diamond") == 0,
                    "disk S' should hold E EXITING and no diamonds: " + d.dat().inventory() + " " + d.account());
            reboot();
            expectConsumed(p, "a durable INTENT");
            problems.expect(EscrowFiles.quarantined(rig, p).isEmpty(), "a valid ledger was quarantined");
            problems.expect(!rig.log().readAll().contains("MTR-0101"), "a valid ledger reported MTR-0101");
        }

        /** K11 with a snapshot that predates E: the stack is still in the inventory; the INTENT finds no entry and does nothing (G3). */
        void k11Rollback() throws Exception {
            String p = "K11rBot";
            seed(p);
            moveIn(p);
            EscrowFiles.Entry e = liveEntry(p); // never saved: the disk is S
            quiet(p);
            appendLedger(p, EscrowFiles.intent(EscrowFiles.txn(1), e.id(), System.currentTimeMillis()));
            expectDiskInInventory(p, "disk S");
            reboot();
            expectReturnedOnce(p, "an INTENT for an entry the snapshot never held (a rollback, not a duplicate)");
        }

        /** K13: the INTENT and a snapshot that already lost E (step 3 ran, a save followed): the record is inert. */
        void k13() throws Exception {
            String p = "K13Bot";
            seed(p);
            moveIn(p);
            EscrowFiles.Entry e = freeze(p, 1);
            // step 3, crafted: the entry leaves the account (gen + 1) and the slot empties, then a save
            EscrowFiles.Account gone = new EscrowFiles.Account(inv(p).account().gen() + 1, List.of());
            rig.probe("adopt", p + " " + EscrowFiles.hex(EscrowFiles.encodeAccount(gone)));
            rig.probe("win", p + " " + slot(p) + " DIAMOND 0");
            save(p);
            quiet(p);
            appendLedger(p, EscrowFiles.intent(EscrowFiles.txn(1), e.id(), System.currentTimeMillis()));
            Disk d = disk(p);
            problems.expect(d.count("minecraft:diamond") == 0 && d.entries() == 0, "disk S'' should hold neither: "
                    + d.dat().inventory() + " " + d.account());
            reboot();
            expectConsumed(p, "a record whose entry is already gone");
            problems.expect(!rig.log().readAll().contains("MTR-0101"), "an inert record reported MTR-0101");
        }

        /**
         * K14: the player quits before the ack. E is EXITING, so the close does not return it (no second copy); the
         * save holds it EXITING. {@code a}: the INTENT lands after the save and recovery drops it. {@code b}: it never
         * lands, and recovery returns it.
         */
        void k14() throws Exception {
            String a = "K14aBot";
            String b = "K14bBot";
            seed(a);
            moveIn(a);
            EscrowFiles.Entry ea = freeze(a, 1);
            seed(b);
            moveIn(b);
            freeze(b, 2);
            rig.probe("leave", a);
            rig.probe("leave", b);
            rig.kill();
            for (String p : List.of(a, b)) {
                Disk d = disk(p);
                problems.expect(d.count("minecraft:diamond") == 0, p + ": the close returned a frozen entry's stack: "
                        + d.dat().inventory());
                problems.expect(d.entries() == 1 && d.sole().state() == EscrowFiles.EXITING,
                        p + ": the save should hold E EXITING: " + d.account());
            }
            appendLedger(a, EscrowFiles.intent(EscrowFiles.txn(1), ea.id(), System.currentTimeMillis()));
            reboot();
            expectConsumed(a, "K14a (INTENT after the save)");
            expectReturnedOnce(b, "K14b (no INTENT ever)");
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

        /** K17: a valid INTENT and then a torn record: the INTENT counts, the tail is ignored (and left for the next writer). */
        void k17() throws Exception {
            String p = "K17Bot";
            EscrowFiles.Entry e = heldAndSaved(p, true);
            quiet(p);
            appendLedger(p, EscrowFiles.intent(EscrowFiles.txn(1), e.id(), System.currentTimeMillis()),
                    EscrowFiles.prefix(EscrowFiles.done(EscrowFiles.txn(1)), 11));
            long before = Files.size(EscrowFiles.ledgerFile(rig, p));
            reboot();
            expectConsumed(p, "an INTENT followed by a torn tail");
            problems.expect(Files.size(EscrowFiles.ledgerFile(rig, p)) == before, "a read rewrote the ledger");
            problems.expect(EscrowFiles.quarantined(rig, p).isEmpty(), "a torn tail was quarantined as corruption");
            problems.expect(!rig.log().readAll().contains("MTR-0101"), "a torn tail reported MTR-0101");
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
            EscrowFiles.Entry e = heldAndSaved(ledgerBad, true);
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
