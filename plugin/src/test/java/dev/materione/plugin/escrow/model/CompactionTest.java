package dev.materione.plugin.escrow.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Row L: which records compaction may drop (gui-input-durability.md §5.3). */
class CompactionTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 100 * DAY;
    private static final long RETENTION = 30 * DAY;

    private static LedgerRecord.Intent intent(long n, long at) {
        return new LedgerRecord.Intent(Fx.id(n), Fx.id(100 + n), ExitKind.CONSUME, Fx.stack("s" + n).hash16(), 1, at);
    }

    private static LedgerRecord.Done done(long n) {
        return new LedgerRecord.Done(Fx.id(n));
    }

    private static Ledger of(LedgerRecord... records) {
        return new Ledger(List.of(records));
    }

    @Test
    void anAcknowledgedExitOlderThanTheRetentionGoesWithItsDone() {
        Ledger kept = Compaction.plan(of(intent(1, NOW - 31 * DAY), done(1)), NOW, RETENTION);
        assertThat(kept.records()).isEmpty();
    }

    @Test
    void anExitInsideTheRetentionIsKeptEvenWhenAcknowledged() {
        Ledger ledger = of(intent(1, NOW - 29 * DAY), done(1));
        assertThat(Compaction.plan(ledger, NOW, RETENTION)).isSameAs(ledger);
    }

    @Test
    void theBoundaryIsInclusive() {
        assertThat(Compaction.plan(of(intent(1, NOW - RETENTION), done(1)), NOW, RETENTION).records()).isEmpty();
        Ledger young = of(intent(1, NOW - RETENTION + 1), done(1));
        assertThat(Compaction.plan(young, NOW, RETENTION)).isSameAs(young);
    }

    @Test
    void anIntentWithoutADoneIsNeverDroppedAtAnyAge() {
        Ledger ledger = of(intent(1, 0));
        assertThat(Compaction.plan(ledger, NOW, RETENTION)).isSameAs(ledger);
        assertThat(Compaction.plan(ledger, NOW, RETENTION).hasIntent(Fx.id(101))).isTrue();
    }

    @Test
    void aDoneWithoutItsIntentIsInertAndDropped() {
        Ledger kept = Compaction.plan(of(done(9), intent(1, NOW)), NOW, RETENTION);
        assertThat(kept.records()).containsExactly(intent(1, NOW));
    }

    @Test
    void aClockBehindTheRecordsDropsNothing() {
        Ledger ledger = of(intent(1, NOW + 10 * DAY), done(1));
        assertThat(Compaction.plan(ledger, NOW, RETENTION)).isSameAs(ledger);
    }

    @Test
    void orderOfWhatIsKeptIsPreserved() {
        LedgerRecord a = intent(1, NOW - 40 * DAY);
        LedgerRecord b = intent(2, NOW - 1 * DAY);
        LedgerRecord c = intent(3, NOW - 60 * DAY);
        Ledger kept = Compaction.plan(of(a, b, done(1), c, done(2)), NOW, RETENTION);
        assertThat(kept.records()).containsExactly(b, c, done(2));
    }

    @Test
    void anEmptyLedgerIsItsOwnPlan() {
        assertThat(Compaction.plan(Ledger.EMPTY, NOW, RETENTION)).isSameAs(Ledger.EMPTY);
    }
}
