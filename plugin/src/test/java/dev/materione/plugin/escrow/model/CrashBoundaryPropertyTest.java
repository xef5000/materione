package dev.materione.plugin.escrow.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * The executable form of the crash-boundary table K1-K23 (gui-input-durability.md §5.10, §8): random sequences of
 * {give, move-in, move-out, pick-up, place, swap, close, claim, edit, reslot, freeze, incoming consume, ledger
 * append, apply, acknowledge, abort-while-undecided, save, death, quit, rejoin}, a {@code kill -9} after every step
 * against every save taken so far (and the join-time disk), with the ledger as it stood and with a torn partial
 * append, and an oracle for G1-G4, I6 and save atomicity ({@link CrashHarness}).
 *
 * <p>The seed set is committed ({@link #SEEDS}); a failure prints its seed and the step trace, and
 * {@code CrashHarness.run(new EscrowWorld(seed, NONE), seed, STEPS)} reproduces it. The negative controls are the
 * point: the shipped next-tick mirror design and two deliberately broken variants of the new one must each be
 * caught, so the oracle is known to be able to fail where the design says it cannot.
 */
class CrashBoundaryPropertyTest {

    /** Seeds are committed: 1..400, plus the ones that once exposed something (none yet; append, never remove). */
    private static final long[] SEEDS = LongStream.rangeClosed(1, 400).toArray();

    private static final int STEPS = 60;

    private static String report(List<CrashHarness.Violation> violations) {
        return violations.stream().limit(3).map(CrashHarness.Violation::text).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void theNewDesignHoldsG1ToG4AtEveryCrashBoundaryAgainstEverySnapshot() {
        var coverage = new CrashHarness.Coverage();
        for (long seed : SEEDS) {
            CrashHarness run = CrashHarness.run(new EscrowWorld(seed, EscrowWorld.Mutation.NONE), seed, STEPS);
            assertThat(run.violations()).as("seed %d%s", seed, report(run.violations())).isEmpty();
            coverage.add(run.coverage);
        }
        // the generator is not vacuous: every interesting recovery shape was exercised
        assertThat(coverage.crashes).isGreaterThan(400_000);
        assertThat(coverage.recoveriesWithAnAccount).as("recoveries that had an account to recover").isGreaterThan(50_000);
        assertThat(coverage.recoveriesWithADecidedEntry).as("G2: an INTENT for an entry the old save still holds").isGreaterThan(1_000);
        assertThat(coverage.recoveriesWithAnIntentButNoEntry).as("G3: an INTENT for an entry the save predates").isGreaterThan(1_000);
        assertThat(coverage.recoveriesWithOwedStacks).as("G4: overflow stayed owed").isGreaterThan(1_000);
        assertThat(coverage.tornVariants).as("K17: torn tails").isGreaterThan(10_000);
    }

    @Test
    void theShippedNextTickMirrorIsCaught() {
        Set<CrashHarness.Kind> caught = new TreeSet<>();
        int failingSeeds = 0;
        for (long seed : SEEDS) {
            CrashHarness run = CrashHarness.run(new OldMirrorWorld(seed), seed, STEPS);
            caught.addAll(run.kinds());
            failingSeeds += run.violations().isEmpty() ? 0 : 1;
        }
        assertThat(caught)
                .as("the old design loses on move-in (K19), duplicates on move-out (W1; the dup is in the save itself) and drops at the feet (W6)")
                .contains(
                        CrashHarness.Kind.LOSS_AT_SAVE,
                        CrashHarness.Kind.DUP_AT_SAVE,
                        CrashHarness.Kind.LOSS_ON_RECOVERY,
                        CrashHarness.Kind.WORLD_WRITE);
        assertThat(failingSeeds).as("most random histories already expose it").isGreaterThan(SEEDS.length / 2);
    }

    @Test
    void k19ByHandAMoveInSavedBeforeItsMirrorIsLostAndAMoveOutSavedBeforeItsMirrorIsDuplicated() {
        var world = new OldMirrorWorld(7);
        String stack = Design.name(world.firstInventoryStack());

        // move-in: the stack leaves the inventory now, the journal is mirrored one tick later, the autosave lands first
        world.scriptedMoveIn();
        Design.Snapshot early = world.scriptedSave(1);
        assertThat(Design.multiset(world.liveHoldings())).containsKey(stack);
        assertThat(world.recover(early, new byte[0]).inventory().stream().map(Design::name)).doesNotContain(stack);

        // move-out: the stack is back in the inventory, the journal still holds it, the autosave lands first
        world.scriptedTick();
        world.scriptedMoveOut();
        Design.Snapshot late = world.scriptedSave(2);
        List<String> recovered = world.recover(late, new byte[0]).inventory().stream().map(Design::name).toList();
        assertThat(java.util.Collections.frequency(recovered, stack))
                .as("the inventory and the journal both hold it, so recovery returns it twice")
                .isEqualTo(2);
    }

    @Test
    void theBrokenVariantsOfTheNewDesignAreCaught() {
        for (var broken : List.of(
                new Broken(EscrowWorld.Mutation.IGNORE_LEDGER, CrashHarness.Kind.RESURRECTION),
                new Broken(EscrowWorld.Mutation.DROP_ON_OVERFLOW, CrashHarness.Kind.WORLD_WRITE))) {
            Set<CrashHarness.Kind> caught = new TreeSet<>();
            for (long seed : SEEDS) {
                caught.addAll(CrashHarness.run(new EscrowWorld(seed, broken.mutation), seed, STEPS).kinds());
            }
            assertThat(caught).as("mutation %s", broken.mutation).contains(broken.expected);
        }
    }

    @Test
    void theDropOnOverflowVariantAlsoLosesStacksSoG1CatchesItToo() {
        Set<CrashHarness.Kind> caught = new TreeSet<>();
        for (long seed : SEEDS) {
            caught.addAll(CrashHarness.run(new EscrowWorld(seed, EscrowWorld.Mutation.DROP_ON_OVERFLOW), seed, STEPS).kinds());
        }
        assertThat(caught).contains(CrashHarness.Kind.LOSS_ON_RECOVERY);
    }

    private record Broken(EscrowWorld.Mutation mutation, CrashHarness.Kind expected) {}
}
