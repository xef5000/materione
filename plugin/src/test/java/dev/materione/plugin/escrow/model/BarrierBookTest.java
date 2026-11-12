package dev.materione.plugin.escrow.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Tier 3's bookkeeping (gui-input-durability.md §5.4, §12 row B): the rate limit, which exits a read-back proves, and
 * K23's rule that anything short of a read {@code gen} acknowledges nothing.
 */
class BarrierBookTest {

    private static final Id128 ENTRY = new Id128(1, 1);

    private static LedgerRecord.Intent intent(long txn) {
        return new LedgerRecord.Intent(new Id128(9, txn), new Id128(ENTRY.hi(), txn), ExitKind.CONSUME,
                new Id128(3, 3), 5, 1_000L);
    }

    private final BarrierBook book = new BarrierBook(5_000, 600_000);

    @Test
    void nothingWaitingMeansNothingToDo() {
        assertThat(book.decide(0)).isInstanceOf(BarrierBook.Idle.class);
    }

    @Test
    void theFirstExitSavesAtOnceAndThenWaitsForItsReadBack() {
        book.register(intent(1), 5, true, 100);

        assertThat(book.decide(100)).isInstanceOf(BarrierBook.Save.class);
        book.saved(100, 5);
        assertThat(book.inFlight()).isTrue();
        assertThat(book.decide(101)).as("no second save while a read-back is in flight").isInstanceOf(BarrierBook.Idle.class);
    }

    @Test
    void aSaveWithinTheCooldownIsRefusedByTheBookItself() {
        book.register(intent(1), 5, true, 0);
        book.saved(0, 5);
        book.readBack(OptionalLong.empty());
        book.register(intent(2), 9, true, 10);

        assertThat(book.decide(1_000)).as("one fruitless cycle doubled the 5 s cooldown").isEqualTo(new BarrierBook.Wait(9_000));
        assertThatThrownBy(() -> book.saved(1_000, 9)).isInstanceOf(IllegalStateException.class);
        assertThat(book.decide(5_000)).isEqualTo(new BarrierBook.Wait(5_000));
        assertThat(book.decide(10_000)).isInstanceOf(BarrierBook.Save.class);
    }

    @Test
    void aSaveWhileAReadBackIsInFlightIsRefused() {
        book.register(intent(1), 5, true, 0);
        book.saved(0, 5);

        assertThatThrownBy(() -> book.saved(60_000, 5)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theCooldownDoublesAfterEveryFruitlessCycleUpToTheCeilingAndResetsOnAnAck() {
        book.register(intent(1), 5, true, 0);
        long now = 0;
        long expected = 5_000;
        for (int cycle = 0; cycle < 8; cycle++) {
            assertThat(book.currentCooldown()).as("cycle %d", cycle).isEqualTo(Math.min(expected, 300_000));
            now += book.currentCooldown();
            assertThat(book.decide(now)).isInstanceOf(BarrierBook.Save.class);
            book.saved(now, 5);
            assertThat(book.readBack(OptionalLong.of(4))).as("stale").isEmpty();
            expected *= 2;
        }
        assertThat(book.currentCooldown()).isEqualTo(300_000);

        now += book.currentCooldown();
        book.saved(now, 5);
        assertThat(book.readBack(OptionalLong.of(5))).hasSize(1);
        assertThat(book.currentCooldown()).as("an acknowledgment resets the backoff").isEqualTo(5_000);
    }

    @Test
    void aReadBackAcknowledgesExactlyTheExitsItsGenReached() {
        book.register(intent(1), 5, true, 0);
        book.register(intent(2), 9, true, 0);
        book.register(intent(3), 7, true, 0);
        book.saved(0, 9);

        List<BarrierBook.Acked> acked = book.readBack(OptionalLong.of(7));

        assertThat(acked).extracting(a -> a.exit().intent().txn().lo()).containsExactly(1L, 3L);
        assertThat(acked).extracting(BarrierBook.Acked::done).containsExactly(
                new LedgerRecord.Done(new Id128(9, 1)), new LedgerRecord.Done(new Id128(9, 3)));
        assertThat(book.waiting()).extracting(BarrierBook.Pending::exitGen).containsExactly(9L);
    }

    @Test
    void k23_aStaleReadBackAnEmptyOneAndAMissingOneAcknowledgeNothing() {
        book.register(intent(1), 5, true, 0);
        book.saved(0, 5);
        assertThat(book.readBack(OptionalLong.of(4))).as("an older gen").isEmpty();
        book.saved(10_000, 5);
        assertThat(book.readBack(OptionalLong.empty())).as("no readable account").isEmpty();
        book.saved(30_000, 5);
        assertThat(book.readBack(OptionalLong.of(0))).as("gen zero").isEmpty();

        assertThat(book.waiting()).as("still waiting: asked is never acknowledged").hasSize(1);
    }

    @Test
    void anExitIsAcknowledgedOnceNeverTwice() {
        book.register(intent(1), 5, true, 0);
        book.saved(0, 5);
        assertThat(book.readBack(OptionalLong.of(5))).hasSize(1);
        book.register(intent(2), 6, true, 10);
        book.saved(5_000, 6);

        assertThat(book.readBack(OptionalLong.of(6))).extracting(a -> a.exit().intent().txn().lo()).containsExactly(2L);
    }

    @Test
    void gensCompareAsUnsignedValues() {
        book.register(intent(1), -1L, true, 0); // 2^64 - 1
        book.register(intent(2), 5, true, 0);
        book.saved(0, -1L);

        List<BarrierBook.Acked> acked = book.readBack(OptionalLong.of(-2L)); // 2^64 - 2 is above 5 and below 2^64 - 1

        assertThat(acked).extracting(a -> a.exit().intent().txn().lo()).containsExactly(2L);
    }

    @Test
    void anExitWaitingTooLongIsGivenUpAndDrainEmptiesTheBook() {
        BarrierBook shortLived = new BarrierBook(1_000, 30_000);
        shortLived.register(intent(1), 5, true, 0);
        shortLived.register(intent(2), 6, true, 20_000);

        assertThat(shortLived.expire(29_999)).isEmpty();
        assertThat(shortLived.expire(30_000)).extracting(p -> p.intent().txn().lo()).containsExactly(1L);
        assertThat(shortLived.drain()).extracting(p -> p.intent().txn().lo()).containsExactly(2L);
        assertThat(shortLived.waiting()).isEmpty();
        assertThat(shortLived.decide(0)).isInstanceOf(BarrierBook.Idle.class);
    }

    @Test
    void aClockThatWentBackwardsWaitsOneFullPeriodInsteadOfSavingEarly() {
        book.register(intent(1), 5, true, 0);
        book.saved(100_000, 5);
        book.readBack(OptionalLong.of(5));
        book.register(intent(2), 6, true, 0);

        assertThat(book.decide(50_000)).isEqualTo(new BarrierBook.Wait(5_000));
    }

    @Test
    void coveredGenIsTheLiveGenOfTheLastSave() {
        assertThat(book.coveredGen()).isEmpty();
        book.register(intent(1), 5, true, 0);
        book.saved(0, 8);

        assertThat(book.coveredGen()).hasValue(8);
    }

    @Test
    void anExitWithNoEffectNeverAsksForASaveItPollsForTheServersOwn() {
        book.register(intent(1), 5, false, 1_000);

        assertThat(book.decide(1_000)).as("the first look is one poll interval after it began")
                .isEqualTo(new BarrierBook.Wait(BarrierBook.POLL_INTERVAL_MILLIS));
        assertThat(book.decide(1_000 + BarrierBook.POLL_INTERVAL_MILLIS)).isInstanceOf(BarrierBook.Poll.class);
        assertThatThrownBy(() -> book.saved(31_000, 5)).as("D8: no save for an exit with no effect")
                .isInstanceOf(IllegalStateException.class);

        book.polled(31_000);
        assertThat(book.decide(31_001)).isInstanceOf(BarrierBook.Idle.class);
        assertThat(book.readBack(OptionalLong.of(4))).as("the server has not saved it yet").isEmpty();
        assertThat(book.currentCooldown()).as("a fruitless poll does not back the saves off").isEqualTo(5_000);
        assertThat(book.decide(40_000)).isEqualTo(new BarrierBook.Wait(21_000));
        book.polled(61_000);
        assertThat(book.readBack(OptionalLong.of(5))).hasSize(1);
    }

    @Test
    void aPollBeforeItIsDueIsRefused() {
        book.register(intent(1), 5, false, 0);

        assertThatThrownBy(() -> book.polled(1_000)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oneSaveForAnEffectAcknowledgesTheExitsWithNoEffectToo() {
        book.register(intent(1), 5, false, 0);
        book.register(intent(2), 6, true, 0);

        assertThat(book.decide(0)).isInstanceOf(BarrierBook.Save.class);
        book.saved(0, 6);

        assertThat(book.readBack(OptionalLong.of(6))).hasSize(2);
    }

    @Test
    void expiryTreatsExitsWithAndWithoutAnEffectAlike() {
        book.register(intent(1), 5, false, 0);
        book.register(intent(2), 6, true, 0);
        book.saved(0, 6);
        book.readBack(OptionalLong.of(5)); // acknowledges exit 1 only

        assertThat(book.waiting()).extracting(p -> p.intent().txn().lo()).containsExactly(2L);
        book.register(intent(3), 7, false, 10);
        assertThat(book.expire(600_010)).hasSize(2);
        assertThat(book.waiting()).isEmpty();
    }

    @Test
    void anExitCannotWaitTwiceAndTheNumbersAreChecked() {
        book.register(intent(1), 5, true, 0);
        assertThatThrownBy(() -> book.register(intent(1), 5, true, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BarrierBook(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BarrierBook(BarrierBook.MAX_COOLDOWN_MILLIS + 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BarrierBook(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(BarrierBook.withDefaults().currentCooldown()).isEqualTo(5_000);
    }
}
