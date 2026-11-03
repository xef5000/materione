package dev.materione.behavior.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.flow.ActionStep;
import dev.materione.behavior.flow.FlowFixture;
import dev.materione.behavior.flow.RequireStep;
import dev.materione.behavior.flow.Step;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * A question step (logic.md §6.6): an action that takes an {@code else:} compiles as a require around itself, so an
 * unanswered question runs its else and ends the run, and an answered one lets the run go on.
 */
class PromptStepTest {

    private final FlowFixture fixture = new FlowFixture();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final FlowInterpreter interpreter = new FlowInterpreter(scheduler, RunBudget.DEFAULT, ignored -> { });
    private final List<CompletableFuture<ActionOutcome>> asked = new ArrayList<>();

    PromptStepTest() {
        fixture.logic.action(new Ask());
    }

    /** A question that resolves when the test says so, the way a prompt waits for a player. */
    private final class Ask implements ActionType<TemplateText> {

        @Override
        public String id() {
            return "ask";
        }

        @Override
        public ActionDescription describe() {
            return new ActionDescription(new SelfDescription("Ask", "", List.of(
                    ParameterSchema.of("text", ParameterType.STRING, true)), "1"), LogicCategory.GUI, "text");
        }

        @Override
        public Threading threading() {
            return Threading.PLAYER_REGION;
        }

        @Override
        public boolean takesElse() {
            return true;
        }

        @Override
        public TemplateText parse(ParamReader params) {
            return params.template("text");
        }

        @Override
        public ActionOutcome execute(TemplateText text, LogicContext context) {
            throw new AssertionError("a question runs through its resumable form");
        }

        @Override
        public CompletionStage<ActionOutcome> executeResumable(TemplateText text, LogicContext context, ReadExecutor reads) {
            var answer = new CompletableFuture<ActionOutcome>();
            asked.add(answer);
            return answer;
        }
    }

    private ParsedRules compile(String body) {
        return fixture.compile("- on: click\n  do:\n" + body);
    }

    private Rule rule(String body) {
        ParsedRules parsed = compile(body);
        assertThat(parsed.problems()).isEmpty();
        return parsed.rules().get(0);
    }

    // --- compile ---------------------------------------------------------------------

    @Test
    void aQuestionWithElseCompilesAsARequireAroundItself() {
        Rule rule = rule("""
                    - ask: { text: "Search" }
                      else:
                        - b: nothing
                    - c: after
                """);

        Step guard = rule.steps().get(0);
        assertThat(guard).isInstanceOf(RequireStep.class);
        var require = (RequireStep) guard;
        assertThat(require.steps()).hasSize(1);
        assertThat(require.steps().get(0)).isInstanceOf(ActionStep.class);
        assertThat(require.blocked()).isFalse();
        assertThat(require.otherwise()).hasSize(1);
        assertThat(rule.steps().get(1).path()).hasToString("behavior.0.do.1");
    }

    @Test
    void aQuestionWithoutElseStillEndsTheRunWhenUnanswered() {
        Rule rule = rule("""
                    - ask: { text: "Search" }
                    - c: after
                """);

        var require = (RequireStep) rule.steps().get(0);
        assertThat(require.otherwise()).isEmpty();
        assertThat(require.steps()).hasSize(1);
    }

    @Test
    void elseOnAnActionThatAsksNothingIsRejectedAsStructure() {
        ParsedRules parsed = compile("""
                    - a: one
                      else:
                        - b: two
                """);

        assertThat(parsed.rules()).isEmpty(); // the rule is skipped, as for any mixed step
        assertThat(parsed.problems()).anySatisfy(problem -> assertThat(problem.code().code()).isEqualTo("MTR-0021"));
    }

    @Test
    void aQuestionTakesNoChanceSoItCannotBeSkippedIntoTheStepsAfterIt() {
        ParsedRules parsed = compile("""
                    - ask: { text: "Search" }
                      chance: 0.5
                    - c: after
                """);

        assertThat(parsed.rules()).isEmpty(); // a structural refusal skips the rule, as a mixed step does
        assertThat(parsed.problems()).anySatisfy(problem -> assertThat(problem.code().code()).isEqualTo("MTR-0021"));
    }

    // --- run -------------------------------------------------------------------------

    @Test
    void anAnsweredQuestionLetsTheRunGoOn() {
        Rule rule = rule("""
                    - a: before
                    - ask: { text: "Search" }
                      else:
                        - b: nothing
                    - c: after
                """);

        FlowRun run = interpreter.start(rule, new MapLogicContext(Map.of()), RunOwner.of("u", "anchor"));
        assertThat(run.isParked()).isTrue();
        assertThat(fixture.ran).containsExactly("a:before");

        asked.get(0).complete(ActionOutcome.OK);
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
        assertThat(fixture.ran).containsExactly("a:before", "c:after");
    }

    @Test
    void anUnansweredQuestionRunsItsElseAndEndsTheRun() {
        Rule rule = rule("""
                    - a: before
                    - ask: { text: "Search" }
                      else:
                        - b: nothing
                    - c: after
                """);

        FlowRun run = interpreter.start(rule, new MapLogicContext(Map.of()), RunOwner.of("u", "anchor"));
        asked.get(0).complete(ActionOutcome.failed("no answer in 60 seconds"));

        assertThat(run.outcome()).isEqualTo(RunOutcome.REQUIRE_FAILED);
        assertThat(fixture.ran).containsExactly("a:before", "b:nothing");
    }

    @Test
    void anUnansweredQuestionWithoutElseEndsTheRunQuietly() {
        Rule rule = rule("""
                    - ask: { text: "Search" }
                    - c: after
                """);

        FlowRun run = interpreter.start(rule, new MapLogicContext(Map.of()), RunOwner.of("u", "anchor"));
        asked.get(0).complete(ActionOutcome.failed("the player cancelled"));

        assertThat(run.outcome()).isEqualTo(RunOutcome.REQUIRE_FAILED);
        assertThat(fixture.ran).isEmpty();
    }
}
