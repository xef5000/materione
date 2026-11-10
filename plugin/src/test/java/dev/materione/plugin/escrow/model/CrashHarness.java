package dev.materione.plugin.escrow.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;

/**
 * The oracle of the crash-boundary property test (gui-input-durability.md §8). It runs a {@link Design} for a
 * number of random steps and, <em>after every step</em>, simulates a {@code kill -9} against <em>every save taken so
 * far</em> (and the join-time disk) with the ledger as it stood, with and without a torn partial append, then checks
 * what a boot recovers:
 *
 * <ul>
 *   <li><b>G1/G3 conservation, bounded rollback</b>: the recovered inventory plus what is still owed is exactly the
 *       saved snapshot's holdings minus the stacks whose consume decision is durable; nothing lost, nothing twice;
 *   <li><b>G2 no resurrection</b>: no stack of an entry with a durable INTENT comes back, however old the save;
 *   <li><b>G4 no world IO</b>: recovery wrote nothing to a world;
 *   <li><b>I6 idempotence</b>: recovering twice is the same, and a crash right after a saved recovery (K15, K16)
 *       recovers to the same holdings;
 *   <li><b>save atomicity (I1, G3 "never half-visible")</b>: every save holds exactly what the player owned at that
 *       instant, because a save can land between any two steps (F6). A snapshot that lost a stack or holds it twice
 *       is a violation <em>before</em> any crash.
 * </ul>
 */
final class CrashHarness {

    /** The kinds of violation, so a negative control can say what it caught. */
    enum Kind {
        LOSS_AT_SAVE,
        DUP_AT_SAVE,
        LOSS_ON_RECOVERY,
        DUP_ON_RECOVERY,
        RESURRECTION,
        WORLD_WRITE,
        NOT_DETERMINISTIC,
        SECOND_CRASH_DIFFERS,
        LIVE_INVARIANT
    }

    record Violation(Kind kind, String text) {}

    /** What a run exercised, so the generator cannot be vacuous. */
    static final class Coverage {
        int crashes;
        int recoveriesWithADecidedEntry;
        int recoveriesWithAnIntentButNoEntry;
        int recoveriesWithOwedStacks;
        int tornVariants;
        int recoveriesWithAnAccount;

        void add(Coverage other) {
            crashes += other.crashes;
            recoveriesWithADecidedEntry += other.recoveriesWithADecidedEntry;
            recoveriesWithAnIntentButNoEntry += other.recoveriesWithAnIntentButNoEntry;
            recoveriesWithOwedStacks += other.recoveriesWithOwedStacks;
            tornVariants += other.tornVariants;
            recoveriesWithAnAccount += other.recoveriesWithAnAccount;
        }
    }

    private static final int MAX_VIOLATIONS = 12;

    private final Design design;
    private final long seed;
    private final List<String> trace = new ArrayList<>();
    private final List<Violation> violations = new ArrayList<>();
    final Coverage coverage = new Coverage();

    private CrashHarness(Design design, long seed) {
        this.design = design;
        this.seed = seed;
    }

    /** Runs {@code steps} steps of {@code design} from {@code seed}; the violations found (empty when it is sound). */
    static CrashHarness run(Design design, long seed, int steps) {
        var harness = new CrashHarness(design, seed);
        var rng = new SplittableRandom(seed);
        harness.crashHere(0);
        int checkedSaves = 0;
        int reportedLive = 0;
        for (int step = 1; step <= steps && harness.violations.size() < MAX_VIOLATIONS; step++) {
            harness.trace.add(step + ":" + design.step(rng, step));
            for (; reportedLive < design.liveViolations().size(); reportedLive++) {
                harness.violate(Kind.LIVE_INVARIANT, design.liveViolations().get(reportedLive));
            }
            for (; checkedSaves < design.saves().size(); checkedSaves++) {
                harness.checkSaveIsAtomic(design.saves().get(checkedSaves));
            }
            harness.crashHere(step);
        }
        return harness;
    }

    List<Violation> violations() {
        return violations;
    }

    Set<Kind> kinds() {
        Set<Kind> kinds = new TreeSet<>();
        violations.forEach(v -> kinds.add(v.kind()));
        return kinds;
    }

    private void violate(Kind kind, String what) {
        violations.add(new Violation(kind, "[" + kind + "] seed " + seed + ", design " + design.getClass().getSimpleName()
                + ": " + what + "\n  trace: " + String.join(" ", trace)));
    }

    // ---- a save must hold what the player owned at that instant ----------------------------------------------

    private void checkSaveIsAtomic(Design.Saved saved) {
        Map<String, Integer> live = Design.multiset(design.liveHoldings());
        Map<String, Integer> disk = Design.multiset(holdings(saved.snapshot()));
        for (String name : union(live, disk)) {
            int onDisk = disk.getOrDefault(name, 0);
            int owned = live.getOrDefault(name, 0);
            if (onDisk < owned) {
                violate(Kind.LOSS_AT_SAVE, "the save after step " + saved.step() + " lacks " + name
                        + " that the player owned (a crash would lose it)");
            } else if (onDisk > owned) {
                violate(Kind.DUP_AT_SAVE, "the save after step " + saved.step() + " holds " + name
                        + " " + onDisk + " times, the player owns it " + owned + " (a crash would duplicate it)");
            }
        }
    }

    private List<StackBytes> holdings(Design.Snapshot snapshot) {
        List<StackBytes> all = new ArrayList<>(snapshot.inventory());
        design.escrowed(snapshot).forEach(held -> all.add(held.stack()));
        return all;
    }

    // ---- kill -9 here, against every snapshot ------------------------------------------------------------------

    private void crashHere(int step) {
        byte[] ledger = design.ledgerFile();
        List<byte[]> variants = new ArrayList<>();
        variants.add(ledger);
        byte[] next = design.nextAppend();
        if (next != null) {
            Set<Integer> cuts = new TreeSet<>(List.of(1, next.length / 2, next.length - 1));
            for (int cut : cuts) {
                if (cut >= 1 && cut < next.length) {
                    variants.add(Design.concat(ledger, java.util.Arrays.copyOf(next, cut)));
                    coverage.tornVariants++;
                }
            }
        }
        List<Design.Snapshot> snapshots = new ArrayList<>();
        snapshots.add(design.initial());
        design.saves().forEach(saved -> snapshots.add(saved.snapshot()));
        for (int s = 0; s < snapshots.size(); s++) {
            for (byte[] file : variants) {
                checkRecovery(step, s, snapshots.get(s), file);
            }
        }
    }

    private void checkRecovery(int step, int snapshotIndex, Design.Snapshot saved, byte[] file) {
        coverage.crashes++;
        String where = "kill after step " + step + ", disk = snapshot #" + snapshotIndex + " ";
        LedgerRead read = LedgerCodec.read(file);
        if (!(read instanceof LedgerRead.Valid valid)) {
            violate(Kind.LIVE_INVARIANT, where + "the harness produced an unreadable ledger: " + read);
            return;
        }
        Ledger ledger = valid.ledger();

        List<Design.Held> escrowed = design.escrowed(saved);
        Set<String> decidedNames = new HashSet<>();
        List<StackBytes> expectedStacks = new ArrayList<>(saved.inventory());
        for (Design.Held held : escrowed) {
            if (held.entryId() != null && ledger.hasIntent(held.entryId())) {
                decidedNames.add(Design.name(held.stack()));
            } else {
                expectedStacks.add(held.stack());
            }
        }
        if (!escrowed.isEmpty()) {
            coverage.recoveriesWithAnAccount++;
        }
        if (!decidedNames.isEmpty()) {
            coverage.recoveriesWithADecidedEntry++;
        } else if (ledger.records().stream().anyMatch(r -> r instanceof LedgerRecord.Intent)) {
            coverage.recoveriesWithAnIntentButNoEntry++;
        }

        Design.Outcome outcome = design.recover(saved, file);
        if (!outcome.owed().isEmpty()) {
            coverage.recoveriesWithOwedStacks++;
        }
        Map<String, Integer> expected = Design.multiset(expectedStacks);
        Map<String, Integer> actual = Design.multiset(Design.concat(outcome.inventory(), outcome.owed()));
        for (String name : union(expected, actual)) {
            int want = expected.getOrDefault(name, 0);
            int got = actual.getOrDefault(name, 0);
            if (got > want && decidedNames.contains(name)) {
                violate(Kind.RESURRECTION, where + name + " came back though its consume decision is durable (G2)");
            } else if (got > want) {
                violate(Kind.DUP_ON_RECOVERY, where + name + " is present " + got + " times, expected " + want + " (G1)");
            } else if (got < want) {
                violate(Kind.LOSS_ON_RECOVERY, where + name + " is present " + got + " times, expected " + want + " (G1)");
            }
        }
        if (outcome.worldWrites() > 0) {
            violate(Kind.WORLD_WRITE, where + "recovery wrote " + outcome.worldWrites() + " stack(s) to a world (G4)");
        }

        Design.Outcome again = design.recover(saved, file);
        if (!Design.multiset(again.inventory()).equals(Design.multiset(outcome.inventory()))
                || !Design.multiset(again.owed()).equals(Design.multiset(outcome.owed()))
                || !java.util.Arrays.equals(again.next().data(), outcome.next().data())) {
            violate(Kind.NOT_DETERMINISTIC, where + "recovering the same inputs twice gave different results (I6)");
        }

        Design.Outcome second = design.recover(outcome.next(), file);
        Map<String, Integer> afterSecond = Design.multiset(Design.concat(second.inventory(), second.owed()));
        if (!afterSecond.equals(actual)) {
            violate(Kind.SECOND_CRASH_DIFFERS, where + "a second crash after a saved recovery changed the holdings from "
                    + actual + " to " + afterSecond + " (K16)");
        }
    }

    private static Set<String> union(Map<String, Integer> a, Map<String, Integer> b) {
        Set<String> names = new TreeSet<>(a.keySet());
        names.addAll(b.keySet());
        return names;
    }
}
