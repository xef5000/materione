package dev.materione.behavior.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The universal step keys and {@code wait} as expressions at load (logic.md section 42, #470, #470): a literal {@code chance},
 * {@code delay} or {@code wait} is checked here exactly as before; a template is compiled with the step's own expression
 * recorder and read when the step runs. Bad syntax skips only that step; a literal out of range still refuses the rule's
 * step at load, as it always did.
 */
class TemplateTimingCompileTest {

    private final FlowFixture f = new FlowFixture();

    private ParsedRules compile(String steps) {
        return f.compile("- on: click\n  do:\n" + steps.lines().map(line -> "    " + line)
                .reduce("", (a, b) -> a + b + "\n"));
    }

    private static List<String> codes(ParsedRules parsed) {
        return parsed.problems().stream().map(problem -> problem.code().code()).toList();
    }

    @Test
    void aLiteralChanceDelayAndWaitCompileAsTheyAlwaysDid() {
        Rule rule = f.clean("""
                - on: click
                  do:
                    - a: x
                      chance: 0.25
                      delay: 4
                    - wait: 7
                """);

        var action = (ActionStep<?>) rule.steps().get(0);
        assertThat(action.chance().isTemplate()).isFalse();
        assertThat(action.chance().literal()).isEqualTo(0.25);
        assertThat(action.delayTicks().literal()).isEqualTo(4);
        assertThat(((WaitStep) rule.steps().get(1)).ticks().isTemplate()).isFalse();
        assertThat(((WaitStep) rule.steps().get(1)).ticks().literal()).isEqualTo(7);
    }

    @Test
    void aTemplateChanceDelayAndWaitCompileAndAreKeptForTheRun() {
        Rule rule = f.clean("""
                - on: click
                  do:
                    - a: x
                      chance: "${player.level / 2}"
                      delay: "${player.level}"
                    - wait: "${player.level * 2}"
                """);

        var action = (ActionStep<?>) rule.steps().get(0);
        assertThat(action.chance().isTemplate()).isTrue();
        assertThat(action.chance().template().source()).isEqualTo("${player.level / 2}");
        assertThat(action.delayTicks().isTemplate()).isTrue();
        assertThat(action.timingIsTemplate()).isTrue();
        assertThat(((WaitStep) rule.steps().get(1)).ticks().isTemplate()).isTrue();
        assertThat(((WaitStep) rule.steps().get(1)).ticks().whole()).isTrue();
    }

    @Test
    void aTemplateTimingReadsItsRootsLikeAnyExpression() {
        // an unknown function is a syntax problem at load, and the step is skipped; the rule keeps its other steps
        ParsedRules parsed = compile("- a: x\n  chance: \"${nope()}\"\n- b: y");

        assertThat(codes(parsed)).isNotEmpty();
        assertThat(parsed.rules()).hasSize(1);
        assertThat(parsed.rules().get(0).steps()).hasSize(1);
    }

    @Test
    void aLiteralChanceOutOfRangeIsStillRefusedAtLoad() {
        ParsedRules parsed = compile("- a: x\n  chance: 2\n- b: y");

        // a structural problem skips the rule (MTR-0021), as a literal chance out of range always has
        assertThat(parsed.problems()).singleElement().satisfies((Diagnostic problem) -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0021");
            assertThat(problem.message()).contains("'chance' must be between 0 and 1");
        });
        assertThat(parsed.rules()).isEmpty();
    }

    @Test
    void aLiteralWaitThatIsTextIsStillRefusedAtLoad() {
        ParsedRules parsed = compile("- wait: lots\n- b: y");

        assertThat(parsed.problems()).singleElement().satisfies((Diagnostic problem) ->
                assertThat(problem.message()).contains("wait needs a whole number of ticks from 0 to 1728000"));
    }

    @Test
    void aTemplateWaitWithBadSyntaxIsSkippedAloneWithItsCode() {
        ParsedRules parsed = compile("- wait: \"${player.level *}\"\n- b: y");

        assertThat(parsed.problems()).singleElement().satisfies((Diagnostic problem) ->
                assertThat(problem.code().code()).isEqualTo("MTR-0022"));
        assertThat(parsed.rules().get(0).steps()).singleElement()
                .satisfies(step -> assertThat(((ActionStep<?>) step).id()).isEqualTo("b"));
    }

    @Test
    void theUniversalKeysAreDeclaredAsExpressionsForTheVocabularyAndTheSdk() {
        assertThat(dev.materione.behavior.rule.RuleParser.ACTION_OPTIONS)
                .allSatisfy(option -> assertThat(option.semantics().expression()).isTrue());
        assertThat(dev.materione.behavior.logic.StepKinds.all().stream()
                .filter(kind -> kind.id().equals("wait")).findFirst().orElseThrow().parameters())
                .singleElement().satisfies(ticks -> assertThat(ticks.semantics().expression()).isTrue());
    }
}
