package dev.materione.behavior.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.rule.ParsedRules;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The sealed condition tree (logic.md §2.3): its shapes, its laziness, its failure mode. */
class ConditionTest {

    private final FlowFixture f = new FlowFixture();
    private final MapLogicContext context = new MapLogicContext(
            Map.of("player", Map.of("level", 12, "name", "Alex"), "vars", Map.of()));

    /** Compiles {@code if:} of a one-step rule and returns the rule-level condition's single child. */
    private Condition condition(String ifValue) {
        var rule = f.clean("- on: click\n  if: " + ifValue + "\n  do: [ { a: x } ]\n");
        return ((AllCond) rule.condition()).of().get(0);
    }

    // --- shapes --------------------------------------------------------------------

    @Test
    void everyShapeCompilesToItsNode() {
        Condition tree = condition("""
                { all: [ { pass: a }, { any: [ "player.level > 1", { not: { deny: b } } ] },
                         { at_least: { count: 1, of: [ { deny: c }, { pass: d } ] } } ] }""");

        var all = (AllCond) tree;
        assertThat(all.of()).hasSize(3);
        assertThat(all.of().get(0)).isInstanceOf(PredicateCond.class);
        var any = (AnyCond) all.of().get(1);
        assertThat(any.of().get(0)).isInstanceOf(ExpressionCond.class);
        assertThat(((NotCond) any.of().get(1)).inner()).isInstanceOf(PredicateCond.class);
        var atLeast = (AtLeastCond) all.of().get(2);
        assertThat(atLeast.count()).isEqualTo(1);
        assertThat(atLeast.of()).hasSize(2);
        assertThat(tree.readsLiveState()).isTrue();
        assertThat(tree.describe()).contains("all(").contains("pass: a").contains("not(").contains("at_least(1");
    }

    @Test
    void anExpressionIsWrittenBareOrAsATemplateAndABooleanIsAConstant() {
        assertThat(((ExpressionCond) condition("\"player.level >= 10\"")).source()).isEqualTo("player.level >= 10");
        assertThat(((ExpressionCond) condition("\"${player.level >= 10}\"")).source())
                .isEqualTo("player.level >= 10");
        assertThat(condition("true").test(context)).isTrue();
        assertThat(condition("false").test(context)).isFalse();
        assertThat(condition("\"player.level >= 10\"").readsLiveState()).isFalse();
    }

    @Test
    void aListIsAll() {
        Condition list = condition("[ [ { pass: a }, { deny: b } ] ]");
        assertThat(list).isInstanceOf(AllCond.class);
        assertThat(list.test(context)).isFalse();
    }

    @Test
    void aTemplateWithTextAroundItIsAStringExpressionNotUnwrapped() {
        // "${a} and ${b}" is not one template; unwrapping it would change its meaning
        assertThat(ConditionCompiler.unwrap("${a} and ${b}")).isEqualTo("${a} and ${b}");
        assertThat(ConditionCompiler.unwrap("${ a && b }")).isEqualTo("a && b");
    }

    @Test
    void badShapesAreRejectedAtLoadWithTheirPath() {
        ParsedRules parsed = f.compile("""
                - on: click
                  if: { pass: a, deny: b }
                  do: [ { a: x } ]
                - on: click
                  if: { all: 5 }
                  do: [ { a: x } ]
                - on: click
                  if: { at_least: { count: -1, of: [] } }
                  do: [ { a: x } ]
                - on: click
                  if: { at_least: { count: 1 } }
                  do: [ { a: x } ]
                - on: click
                  if: 7
                  do: [ { a: x } ]
                - on: click
                  if: { nope: 1 }
                  do: [ { a: x } ]
                - on: click
                  if: { pass: reject }
                  do: [ { a: x } ]
                """);

        assertThat(parsed.rules()).isEmpty();
        assertThat(parsed.problems().stream().map(problem -> problem.code().code()))
                .containsExactly("MTR-0021", "MTR-0021", "MTR-0021", "MTR-0021", "MTR-0021", "MTR-0057", "MTR-0057");
        assertThat(parsed.problems().get(1).target().field()).containsExactly("behavior", "1", "if", "all");
        assertThat(parsed.problems().get(5).message()).contains("unknown condition 'nope'");
        assertThat(parsed.problems().get(6).message()).contains("condition 'pass' rejected");
    }

    @Test
    void aConditionGuardedStepCanUseARegisteredPredicateInARuleLevelIf() {
        var rule = f.clean("- on: click\n  if: [ { pass: a } ]\n  do: [ { a: x } ]\n");
        assertThat(rule.condition().readsLiveState()).isTrue();
    }

    // --- laziness and failure --------------------------------------------------------

    @Test
    void allStopsAtTheFirstFalse() {
        assertThat(condition("{ all: [ { pass: 1 }, { deny: 2 }, { pass: 3 } ] }").test(context)).isFalse();
        assertThat(f.asked).containsExactly("pass:1", "deny:2");
    }

    @Test
    void anyStopsAtTheFirstTrue() {
        assertThat(condition("{ any: [ { deny: 1 }, { pass: 2 }, { pass: 3 } ] }").test(context)).isTrue();
        assertThat(f.asked).containsExactly("deny:1", "pass:2");
    }

    @Test
    void atLeastStopsOnceTheCountIsReached() {
        assertThat(condition("{ at_least: { count: 2, of: [ { pass: 1 }, { pass: 2 }, { pass: 3 } ] } }")
                .test(context)).isTrue();
        assertThat(f.asked).containsExactly("pass:1", "pass:2");
    }

    @Test
    void atLeastStopsOnceTheCountCanNoLongerBeReached() {
        assertThat(condition("{ at_least: { count: 2, of: [ { deny: 1 }, { deny: 2 }, { pass: 3 } ] } }")
                .test(context)).isFalse();
        assertThat(f.asked).containsExactly("deny:1", "deny:2"); // one left cannot make two
    }

    @Test
    void atLeastZeroHoldsWithoutAskingAnyone() {
        assertThat(condition("{ at_least: { count: 0, of: [ { pass: 1 } ] } }").test(context)).isTrue();
        assertThat(f.asked).isEmpty();
    }

    @Test
    void emptyCompositesFollowLogic() {
        assertThat(condition("{ all: [] }").test(context)).isTrue();
        assertThat(condition("{ any: [] }").test(context)).isFalse();
    }

    @Test
    void notInverts() {
        assertThat(condition("{ not: { deny: 1 } }").test(context)).isTrue();
        assertThat(condition("{ not: \"player.level > 50\" }").test(context)).isTrue();
    }

    @Test
    void aLeafThatThrowsMakesTheWholeTestThrowSoNotCannotTurnItIntoYes() {
        Condition broken = condition("{ not: { boomy: boom } }");

        assertThatThrownBy(() -> broken.test(context)).isInstanceOf(IllegalStateException.class);
        // the expression engine's type errors take the same road
        assertThatThrownBy(() -> condition("\"'a' * 3\"").test(context)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void anExpressionReadsTheContextItIsGiven() {
        assertThat(condition("\"player.level >= 10 && player.name == 'Alex'\"").test(context)).isTrue();
        assertThat(condition("\"player.level >= 10 && player.name == 'Bo'\"").test(context)).isFalse();
        assertThat(condition("\"vars.missing\"").test(context)).isFalse(); // absent is data, not an error
        assertThat(List.of(condition("true")).get(0)).isInstanceOf(ExpressionCond.class);
    }
}
