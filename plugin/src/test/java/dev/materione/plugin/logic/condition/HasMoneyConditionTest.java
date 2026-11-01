package dev.materione.plugin.logic.condition;

import static dev.materione.plugin.logic.condition.ConditionFixtures.params;
import static dev.materione.plugin.logic.condition.ConditionFixtures.reader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.plugin.logic.action.PlayerFake;
import dev.materione.plugin.logic.action.ProviderActionsTest;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.plugin.logic.GatewayRunScheduler;
import dev.materione.plugin.logic.currency.CurrencyReads;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.CurrencyReadCapability;
import dev.materione.behavior.run.RunReadExecutor;
import dev.materione.behavior.run.RunScheduler;
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
    void foliaDoesNotTreatAnUnverifiedGlobalProviderAsSafe() {
        var probe = new GatewayRunScheduler.ThreadProbe() {
            @Override public boolean ownsEntity(org.bukkit.entity.Entity entity) { return true; }
            @Override public boolean onGlobalThread() { return true; }
        };
        var guarded = new HasMoneyCondition(providers, new CurrencyReads(true, probe));
        var fake = new PlayerFake();
        assertThatThrownBy(() -> guarded.test(guarded.parse(reader(params("amount", 1))), fake.context()))
                .isInstanceOf(ReadUnavailableException.class).hasMessageContaining("not declared");
        assertThat(guarded.availability().available()).isFalse();
        assertThatThrownBy(() -> guarded.testResumable(guarded.parse(reader(params("amount", 1))),
                fake.context(), new RunReadExecutor(new RunScheduler() {
                    @Override public boolean canRunInline(Object anchor, Threading owner) { return true; }
                    @Override public Handle schedule(Object anchor, Threading owner, long ticks,
                            Runnable task, Runnable retired) { throw new AssertionError(); }
                }, "player")).toCompletableFuture().join()).hasCauseInstanceOf(ReadUnavailableException.class);
    }

    @Test
    void resumablePredicateHopsToItsProviderAndReadsTheLatestBalance() {
        var fake = new PlayerFake();
        providers.backend.readCapability =
                new CurrencyReadCapability(Threading.GLOBAL, Availability.AVAILABLE, null);
        final boolean[] global = {false};
        final Runnable[] pending = {null};
        RunScheduler scheduler = new RunScheduler() {
            @Override public boolean canRunInline(Object anchor, Threading owner) {
                return owner == Threading.ANY || owner == Threading.GLOBAL && global[0];
            }
            @Override public Handle schedule(Object anchor, Threading owner, long ticks, Runnable task, Runnable retired) {
                assertThat(owner).isEqualTo(Threading.GLOBAL);
                pending[0] = task;
                return () -> pending[0] = null;
            }
        };
        var probe = new GatewayRunScheduler.ThreadProbe() {
            @Override public boolean ownsEntity(org.bukkit.entity.Entity entity) { return !global[0]; }
            @Override public boolean onGlobalThread() { return global[0]; }
        };
        var guarded = new HasMoneyCondition(providers, new CurrencyReads(true, probe));
        var result = guarded.testResumable(guarded.parse(reader(params("amount", 5))), fake.context(),
                new RunReadExecutor(scheduler, "player")).toCompletableFuture();
        assertThat(result).isNotDone();
        providers.backend.balances.put(fake.uuid, 8.0);
        global[0] = true;
        pending[0].run();
        assertThat(result.join()).isTrue();
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
