package dev.materione.plugin.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Stack-split and amount-clamp math for hand-outs — pure, pinned here. */
class GiveTest {

    @Test
    void zeroAmountSplitsToNothing() {
        assertThat(Give.split(0, 64)).isEmpty();
    }

    @Test
    void amountsWithinOneStackStaySingle() {
        assertThat(Give.split(64, 64)).containsExactly(64);
        assertThat(Give.split(1, 16)).containsExactly(1);
        assertThat(Give.split(15, 16)).containsExactly(15);
    }

    @Test
    void overflowSpillsIntoFullerFirst() {
        assertThat(Give.split(100, 64)).containsExactly(64, 36);
        assertThat(Give.split(2304, 64)).hasSize(36);
        assertThat(Give.split(70, 16)).containsExactly(16, 16, 16, 16, 6);
    }

    @Test
    void nonStandardMaxSizesAreRespected() {
        assertThat(Give.split(33, 1)).hasSize(33).containsOnly(1);
        assertThat(Give.split(5, 3)).containsExactly(3, 2);
    }

    @Test
    void rejectsNonPositiveMaxSize() {
        assertThatThrownBy(() -> Give.split(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amountClampBoundsAndFallbacks() {
        assertThat(Give.clampAmount("32")).isEqualTo(32);
        assertThat(Give.clampAmount("99999")).isEqualTo(Give.MAX_AMOUNT);
        assertThat(Give.clampAmount("-5")).isOne();
        assertThat(Give.clampAmount("not-a-number")).isOne();
    }
}
