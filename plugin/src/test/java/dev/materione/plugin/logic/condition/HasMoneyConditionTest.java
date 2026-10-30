package dev.materione.plugin.logic.condition;

import static dev.materione.plugin.logic.condition.ConditionFixtures.params;
import static dev.materione.plugin.logic.condition.ConditionFixtures.reader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.plugin.logic.action.PlayerFake;
import dev.materione.plugin.logic.action.ProviderActionsTest;
import org.junit.jupiter.api.Test;

/**
 * {@code has_money} (logic.md §22): the shorthand, the currency resolution shared with give_money and
 * take_money, and the false answers for an unknown or unavailable currency.
 */
class HasMoneyConditionTest {

    private final ProviderActionsTest.FakeProviders providers =
            new ProviderActionsTest.FakeProviders(new ProviderActionsTest.FakePermissions());
    private final HasMoneyCondition condition = new HasMoneyCondition(providers);

    @Test
    void theShorthandIsTheAmountAndTheDefaultCurrencyIsUsedWhenNoneIsNamed() {
        var parsed = condition.parse(reader(params("amount", 250)));
        assertThat(parsed.amount()).isEqualTo(250.0);
        assertThat(parsed.currency()).isNull();
    }

    @Test
    void holdsWhenTheBalanceReachesTheAmountAndNotBeyond() {
        var fake = new PlayerFake();
        providers.backend.balances.put(fake.uuid, 300.0);

        assertThat(condition.test(condition.parse(reader(params("amount", 250))), fake.context())).isTrue();
        assertThat(condition.test(condition.parse(reader(params("amount", 300))), fake.context())).isTrue();
        assertThat(condition.test(condition.parse(reader(params("amount", 301))), fake.context())).isFalse();
    }

    @Test
    void anUnknownCurrencyIsFalseNotAnError() {
        var fake = new PlayerFake();
        providers.backend.balances.put(fake.uuid, 300.0);
        var parsed = condition.parse(reader(params("amount", 1, "currency", "gems")));
        assertThat(condition.test(parsed, fake.context())).isFalse();
    }

    @Test
    void anUnavailableCurrencyIsFalseSoAMissingEconomyNeverGrants() {
        var fake = new PlayerFake();
        providers.backend.balances.put(fake.uuid, 300.0);
        providers.vaultInstalled = false;
        assertThat(condition.test(condition.parse(reader(params("amount", 1))), fake.context())).isFalse();
    }

    @Test
    void withoutAPlayerItIsFalse() {
        assertThat(condition.test(condition.parse(reader(params("amount", 1))), PlayerFake.noPlayer())).isFalse();
    }

    @Test
    void rejectsAMissingNonPositiveOrOversizedAmount() {
        assertThatThrownBy(() -> condition.parse(reader(params("currency", "gems"))))
                .hasMessage("amount is required");
        assertThatThrownBy(() -> condition.parse(reader(params("amount", 0))))
                .hasMessage("amount must be above 0");
        assertThatThrownBy(() -> condition.parse(reader(params("amount", -5))))
                .hasMessageContaining("amount must be a finite number");
        assertThatThrownBy(() -> condition.parse(reader(params("amount", 2.0e12))))
                .hasMessageContaining("amount must be a finite number");
    }

    @Test
    void describesItselfInTheEconomyCategoryWithAmountAsShorthand() {
        var description = condition.describe();
        assertThat(description.shorthand()).isEqualTo("amount");
        assertThat(description.category().id()).isEqualTo("economy");
    }
}
