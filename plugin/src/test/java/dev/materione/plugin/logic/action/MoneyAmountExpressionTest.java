package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.CodedRefusal;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.run.RunReadExecutor;
import dev.materione.behavior.run.RunScheduler;
import dev.materione.plugin.logic.action.ProviderActionsTest.FakePermissions;
import dev.materione.plugin.logic.action.ProviderActionsTest.FakeProviders;
import dev.materione.plugin.logic.condition.HasMoneyCondition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The money amount as an expression (logic.md §42, #464): {@code give_money}, {@code take_money} and {@code has_money}
 * take a number or a template that renders to one, the way {@code give_exp} does. A template that renders to text, to
 * zero or to a negative number fails the step (and is false for the condition), and moves nothing. The fake economy is
 * {@link ProviderActionsTest}'s, so no server is needed.
 */
class MoneyAmountExpressionTest {

    private final FakeProviders providers = new FakeProviders(new FakePermissions());
    private final PlayerFake fake = new PlayerFake();
    private final GiveMoneyAction give = new GiveMoneyAction(providers);
    private final TakeMoneyAction take = new TakeMoneyAction(providers);
    private final HasMoneyCondition has = new HasMoneyCondition(providers);

    private static ParamReader reader(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return ParamReader.of(map, EngineExpressions.INSTANCE);
    }

    /** The run's player, with {@code args.amount} set as a typed command argument would be (here, a number or text). */
    private LogicContext withAmount(Object amount) {
        return new MapLogicContext(Map.of("args", Map.of("amount", amount))).with(Facets.PLAYER, fake.player);
    }

    /** A scheduler that runs every read inline: the resumable path without a server. */
    private static final RunScheduler INLINE = new RunScheduler() {
        @Override
        public boolean canRunInline(Object anchor, Threading thread) {
            return true;
        }

        @Override
        public RunScheduler.Handle schedule(Object anchor, Threading thread, long delayTicks, Runnable task,
                Runnable dropped) {
            throw new AssertionError("nothing in this test is scheduled");
        }
    };

    private static String reasonOf(ActionOutcome outcome) {
        assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
        return ((ActionOutcome.Failed) outcome).reason();
    }

    @Test
    void theAmountIsDeclaredAsAnExpressionOnEveryMoneyEntry() {
        for (var parameters : List.of(
                give.describe().self().parameters(), take.describe().self().parameters(),
                has.describe().self().parameters())) {
            ParameterSchema amount = parameters.stream().filter(schema -> schema.id().equals("amount"))
                    .findFirst().orElseThrow();
            assertThat(amount.semantics().expression()).isTrue();
        }
        assertThat(give.describe().shorthand()).isEqualTo("amount");
        assertThat(take.describe().shorthand()).isEqualTo("amount");
        assertThat(has.describe().shorthand()).isEqualTo("amount");
    }

    @Test
    void aTemplateIsParsedAsAnExpressionAndTextWithoutOneIsRefusedAtLoadAsBefore() {
        var parsed = give.parse(reader("amount", "${args.amount}"));
        assertThat(parsed.amount().template()).isNotNull();
        assertThat(parsed.amount().template().source()).isEqualTo("${args.amount}");
        assertThat(take.parse(reader("amount", "${args.amount}")).amount().template()).isNotNull();
        assertThat(has.parse(reader("amount", "${args.amount}")).amount().template()).isNotNull();

        assertThatThrownBy(() -> give.parse(reader("amount", "100")))
                .hasMessageStartingWith("amount must be a finite number");
        assertThatThrownBy(() -> take.parse(reader("amount", "lots")))
                .hasMessageStartingWith("amount must be a finite number");
        // an expression the compiler cannot read (an unknown function) is refused at load, named by its key
        assertThatThrownBy(() -> give.parse(reader("amount", "${nope()}")))
                .hasMessageStartingWith("amount:");
    }

    @Test
    void aTemplateThatRendersToANumberPaysAndTakesThatAmount() {
        providers.backend.balances.put(fake.uuid, 10d);

        assertThat(give.execute(give.parse(reader("amount", "${args.amount}")), withAmount(25.0)))
                .isEqualTo(ActionOutcome.OK);
        assertThat(providers.backend.balances).containsEntry(fake.uuid, 35d);

        assertThat(take.execute(take.parse(reader("amount", "${args.amount}")), withAmount("5")))
                .isEqualTo(ActionOutcome.OK);
        assertThat(providers.backend.balances).containsEntry(fake.uuid, 30d);
    }

    @Test
    void aTemplateThatRendersToTextZeroOrANegativeNumberFailsTheStepAndMovesNothing() {
        providers.backend.balances.put(fake.uuid, 10d);
        for (Object bad : List.of("lots", "", "-3", -5.0, 0.0, Double.NaN, 2.0e12)) {
            var given = reasonOf(give.execute(give.parse(reader("amount", "${args.amount}")), withAmount(bad)));
            assertThat(given).startsWith("amount must be a number above 0 and at most");
            assertThat(reasonOf(take.execute(take.parse(reader("amount", "${args.amount}")), withAmount(bad))))
                    .startsWith("amount must be a number above 0 and at most");
        }
        assertThat(reasonOf(give.execute(give.parse(reader("amount", "${args.amount}")), withAmount("lots"))))
                .contains("\"lots\"");
        assertThat(providers.backend.balances).containsEntry(fake.uuid, 10d);
    }

    @Test
    void hasMoneyHoldsWhenTheBalanceReachesATemplateAmountAndRefusesAnyOtherRenderingWithItsCode() {
        providers.backend.balances.put(fake.uuid, 300d);
        var parsed = has.parse(reader("amount", "${args.amount}"));

        assertThat(has.test(parsed, withAmount(250.0))).isTrue();
        assertThat(has.test(parsed, withAmount(300.0))).isTrue();
        assertThat(has.test(parsed, withAmount(301.0))).isFalse();
        for (Object bad : List.of("lots", "", -5.0, 0.0)) {
            assertThatThrownBy(() -> has.test(parsed, withAmount(bad)))
                    .as("has_money with %s", bad)
                    .isInstanceOfSatisfying(CodedRefusal.class, refusal -> {
                        assertThat(refusal.code()).isEqualTo(DiagnosticCodes.MONEY_AMOUNT_REFUSED);
                        assertThat(refusal.getMessage()).startsWith("amount must be a number above 0");
                    });
        }
    }

    @Test
    void theRefusedOutcomeCarriesTheMoneyCodeAndAnOrdinaryFailureDoesNot() {
        var refused = give.execute(give.parse(reader("amount", "${args.amount}")), withAmount("lots"));
        assertThat(refused).isInstanceOfSatisfying(ActionOutcome.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo(DiagnosticCodes.MONEY_AMOUNT_REFUSED);
            assertThat(failed.reason()).startsWith("amount must be a number above 0");
        });
        // an insufficient balance is an ordinary failure: no diagnostic code, so nothing is reported beyond the trace
        providers.backend.balances.put(fake.uuid, 1d);
        var insufficient = take.execute(take.parse(reader("amount", 5)), fake.context());
        assertThat(insufficient).isInstanceOfSatisfying(ActionOutcome.Failed.class,
                failed -> assertThat(failed.code()).isNull());
    }

    @Test
    void aLiteralAmountStillPaysThroughTheResumablePath() {
        providers.backend.balances.put(fake.uuid, 10d);
        var reads = new RunReadExecutor(INLINE, "player");

        assertThat(give.executeResumable(give.parse(reader("amount", 4)), fake.context(), reads)
                .toCompletableFuture().join()).isEqualTo(ActionOutcome.OK);
        assertThat(providers.backend.balances).containsEntry(fake.uuid, 14d);
    }

    @Test
    void aTemplateAmountIsRenderedBeforeTheDepositOrTheWithdrawalRuns() {
        providers.backend.balances.put(fake.uuid, 10d);
        var reads = new RunReadExecutor(INLINE, "player");

        assertThat(give.executeResumable(give.parse(reader("amount", "${args.amount}")), withAmount(7.0), reads)
                .toCompletableFuture().join()).isEqualTo(ActionOutcome.OK);
        assertThat(providers.backend.balances).containsEntry(fake.uuid, 17d);

        assertThat(take.executeResumable(take.parse(reader("amount", "${args.amount}")), withAmount(-7.0), reads)
                .toCompletableFuture().join()).isInstanceOf(ActionOutcome.Failed.class);
        assertThat(providers.backend.balances).containsEntry(fake.uuid, 17d);
    }

    @Test
    void hasMoneyRendersItsTemplateBeforeTheBalanceIsRead() {
        providers.backend.balances.put(fake.uuid, 300d);
        var reads = new RunReadExecutor(INLINE, "player");
        var parsed = has.parse(reader("amount", "${args.amount}"));

        assertThat(has.testResumable(parsed, withAmount(120.0), reads).toCompletableFuture().join()).isTrue();
        assertThat(has.testResumable(parsed, withAmount(999.0), reads).toCompletableFuture().join()).isFalse();
        // a refused amount fails the stage with its code, which the engine reports and the condition counts as false
        assertThatThrownBy(() -> has.testResumable(parsed, withAmount("lots"), reads).toCompletableFuture().join())
                .isInstanceOfSatisfying(java.util.concurrent.CompletionException.class, failure ->
                        assertThat(failure.getCause()).isInstanceOfSatisfying(CodedRefusal.class,
                                refusal -> assertThat(refusal.code()).isEqualTo(DiagnosticCodes.MONEY_AMOUNT_REFUSED)));
    }
}
