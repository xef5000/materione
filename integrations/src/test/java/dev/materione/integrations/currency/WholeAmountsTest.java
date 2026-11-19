package dev.materione.integrations.currency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WholeAmountsTest {

    @Test
    void aWholeAmountPassesAsALong() {
        assertThat(WholeAmounts.of(25, Integer.MAX_VALUE)).hasValue(25L);
        assertThat(WholeAmounts.of(0, Integer.MAX_VALUE)).hasValue(0L);
    }

    @Test
    void aFractionIsRefusedNotRounded() {
        assertThat(WholeAmounts.of(2.5, Integer.MAX_VALUE)).isEmpty();
        assertThat(WholeAmounts.of(0.1, Integer.MAX_VALUE)).isEmpty();
    }

    @Test
    void aNegativeNonFiniteOrOversizedAmountIsRefused() {
        assertThat(WholeAmounts.of(-1, Integer.MAX_VALUE)).isEmpty();
        assertThat(WholeAmounts.of(Double.NaN, Integer.MAX_VALUE)).isEmpty();
        assertThat(WholeAmounts.of(Double.POSITIVE_INFINITY, Long.MAX_VALUE)).isEmpty();
        assertThat(WholeAmounts.of(3_000_000_000d, Integer.MAX_VALUE)).isEmpty();
    }

    @Test
    void theRefusalNamesTheCurrencyAndTheAmount() {
        assertThat(WholeAmounts.refusal("PlayerPoints", 2.5)).contains("PlayerPoints").contains("2.5");
    }
}
