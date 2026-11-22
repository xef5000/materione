package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Money whose amount comes from a command argument on a real Paper server (logic.md section 42, #464): a payment moves stub
 * coins between two players, a short balance takes nothing, and a refused amount (text, zero, negative, oversized) moves
 * nothing and is reported under {@code MTR-0120}. See {@link EconomyRunner}.
 */
@Tag("integration")
class PaperEconomyIT {

    @Test
    void aMoneyAmountFromAnArgumentMovesBalancesAndARefusedOneMovesNothing() {
        HarnessResult result = new EconomyRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
