package dev.materione.behavior.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.Threading;
import dev.materione.behavior.logic.LogicFixture;
import dev.materione.behavior.rule.EventSelector;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.RuleOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rule compilation is pure and fail-soft: broken rules become problems, siblings load. */
class FlowCompilerTest {

    private final NamespacedId owner = NamespacedId.of("sample", "wand");
    private final List<String> ran = new ArrayList<>();
    private final LogicFixture logic = new LogicFixture()
            .action(LogicFixture.recording("run_command", Threading.GLOBAL, ran))
            .action(LogicFixture.recording("message", Threading.PLAYER_REGION, ran))
            .action(LogicFixture.recording("sound", Threading.PLAYER_REGION, ran))
            .action(new LogicFixture.CancelFake())
            .action("example", LogicFixture.recording("greet", Threading.PLAYER_REGION, ran));

    private ParsedRules compile(Object section) {
        return FlowCompiler.compile(owner, section, logic.lookup());
    }

    @Test
    void parsesACompleteRule() {
        ParsedRules parsed = compile(List.of(Map.of(
                "id", "boom",
                "on", "click:right",
                "if", List.of("player.sneaking == false"),
                "do", List.of(
                        Map.of("run_command", "say hi", "chance", 0.5, "delay", 10),
                        Map.of("message", Map.of("value", "&aDone", "target", "actionbar"))),
                "cooldown", 1000,
                "charges", 3,
                "per", "global")));

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.rules()).hasSize(1);
        Rule rule = parsed.rules().get(0);
        assertThat(rule.id()).isEqualTo("boom");
        assertThat(rule.on().type()).isEqualTo("click");
        assertThat(rule.on().detail()).contains("right");
        assertThat(rule.condition()).isInstanceOf(AllCond.class);
        assertThat(((AllCond) rule.condition()).of()).hasSize(1);
        assertThat(rule.steps()).hasSize(2);

        ActionStep<?> first = (ActionStep<?>) rule.steps().get(0);
        assertThat(first.id()).isEqualTo("run_command");
        assertThat(first.params()).isEqualTo("say hi"); // the scalar landed on the shorthand parameter
        assertThat(first.chance()).isEqualTo(0.5);
        assertThat(first.delayTicks()).isEqualTo(10);
        assertThat(first.threading()).isEqualTo(Threading.GLOBAL);

        ActionStep<?> second = (ActionStep<?>) rule.steps().get(1);
        assertThat(second.id()).isEqualTo("message");
        assertThat(second.params()).isEqualTo("&aDone");

        assertThat(rule.options().cooldownMs()).isEqualTo(1000);
        assertThat(rule.options().charges()).isEqualTo(3);
        assertThat(rule.options().scope()).isEqualTo(RuleOptions.PER_GLOBAL);
    }

    @Test
    void defaultsApplyWhenOptionsAreAbsent() {
        ParsedRules parsed = compile(List.of(Map.of(
                "on", "consume",
                "do", List.of(Map.of("sound", "minecraft:entity.player.levelup")))));

        Rule rule = parsed.rules().get(0);
        assertThat(rule.id()).contains("#"); // fallback identity <owner>#<index>
        assertThat(rule.on().detail()).isEmpty();
        assertThat(((ActionStep<?>) rule.steps().get(0)).chance()).isEqualTo(1.0);
        assertThat(rule.options()).isEqualTo(RuleOptions.none());
    }

    @Test
    void aSingleConditionStringIsAcceptedListForm() {
        ParsedRules parsed = compile(List.of(Map.of(
                "on", "drop",
                "if", "item.amount > 1",
                "do", List.of(Map.of("cancel", true)))));
        assertThat(parsed.problems()).isEmpty();
        assertThat(((AllCond) parsed.rules().get(0).condition()).of()).hasSize(1);
    }

    @Test
    void brokenRulesAreProblemsAndSiblingsStillLoad() {
        ParsedRules parsed = compile(List.of(
                "not-a-mapping",
                Map.of("if", List.of("true"), "do", List.of()),                 // missing on:
                Map.of("on", "clik", "do", List.of(Map.of("cancel", true))),    // typo'd event
                Map.of("on", "click", "if", List.of("1 +"), "do",
                        List.of(Map.of("cancel", true))),                       // bad expression
                Map.of("on", "click", "do", List.of()),                         // no actions
                Map.of("on", "click", "do",
                        List.of(Map.of("chance", 0.5))),                        // action without type key
                Map.of("on", "click:left", "do", List.of(Map.of("cancel", true))))); // the survivor

        assertThat(parsed.rules()).hasSize(1);
        assertThat(parsed.rules().get(0).on().matches("click", "left")).isTrue();
        assertThat(parsed.problems()).hasSize(6);
        for (Diagnostic problem : parsed.problems()) {
            assertThat(problem.code().code()).isIn("MTR-0021", "MTR-0022");
            assertThat(problem.hint()).isNotBlank();
        }
    }

    @Test
    void nonListSectionsAreEmptyNotFatal() {
        assertThat(compile(null).isEmpty()).isTrue();
        assertThat(compile("behavior").isEmpty()).isTrue();
        assertThat(compile(List.of()).isEmpty()).isTrue();
    }

    @Test
    void selectorMatchingHonoursDetailWildcards() {
        var any = new EventSelector("click", java.util.Optional.empty());
        var right = new EventSelector("click", java.util.Optional.of("right"));
        assertThat(any.matches("click", "left")).isTrue();
        assertThat(right.matches("click", "left")).isFalse();
        assertThat(right.matches("break", "")).isFalse();
    }

    // --- typed parse (logic.md §4.1) ---------------------------------------------

    @Test
    void anUnknownActionSkipsOnlyThatStepWithMtr0057() {
        ParsedRules parsed = compile(List.of(Map.of(
                "on", "click",
                "do", List.of(Map.of("mystery", "x"), Map.of("message", "kept")))));

        assertThat(parsed.rules()).hasSize(1);
        assertThat(parsed.rules().get(0).steps()).extracting(Step::kind).containsExactly("action");
        assertThat(ids(parsed.rules().get(0))).containsExactly("message");
        assertThat(parsed.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0057");
            assertThat(problem.message()).contains("unknown action 'mystery'").contains("sample:wand");
        });
    }

    @Test
    void aTypeThatRejectsItsParametersSkipsOnlyThatStep() {
        ParsedRules parsed = compile(List.of(Map.of(
                "on", "click",
                "do", List.of(Map.of("message", "reject"), Map.of("message", "kept")))));

        assertThat(parsed.rules().get(0).steps()).hasSize(1);
        assertThat(parsed.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0057");
            assertThat(problem.message()).contains("action 'message' rejected: value must not be 'reject'");
            assertThat(problem.hint()).isNotBlank();
        });
    }

    @Test
    void aRuleWhoseStepsAllFailToLoadIsSkippedAndSaysSo() {
        ParsedRules parsed = compile(List.of(Map.of("on", "click", "do", List.of(Map.of("mystery", "x")))));

        assertThat(parsed.rules()).isEmpty();
        assertThat(parsed.problems()).extracting(problem -> problem.code().code())
                .containsExactly("MTR-0057", "MTR-0021");
    }

    @Test
    void aBareValueForAnActionWithoutShorthandIsRejectedAtLoad() {
        logic.action(new FlagOnly());
        ParsedRules parsed = compile(List.of(Map.of("on", "click", "do", List.of(Map.of("flag_only", "x")))));

        assertThat(parsed.rules()).isEmpty();
        assertThat(parsed.problems().get(0).message()).contains("takes a mapping of parameters");
    }

    @Test
    void anAddonActionIsWrittenQualifiedAndNeverShadowsABuiltIn() {
        ParsedRules parsed = compile(List.of(Map.of(
                "on", "click",
                "do", List.of(Map.of("example:greet", "hi"), Map.of("greet", "bare")))));

        assertThat(ids(parsed.rules().get(0))).containsExactly("example:greet");
        assertThat(parsed.problems()).singleElement()
                .satisfies(problem -> assertThat(problem.message()).contains("unknown action 'greet'"));
    }

    @Test
    void addonTriggersAreQualifiedAndTakeTheirDetailAfterASecondColon() {
        logic.registries().registry(dev.materione.api.registry.RegistryKeys.TRIGGERS,
                dev.materione.api.logic.TriggerType.class).register(
                        NamespacedId.of("example", "storm"), new StormTrigger(),
                        dev.materione.api.registry.SelfDescription.of("Storm", "A storm"), "example");

        ParsedRules parsed = compile(List.of(Map.of(
                "on", "example:storm:heavy", "do", List.of(Map.of("message", "x")))));

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.rules().get(0).on().type()).isEqualTo("example:storm");
        assertThat(parsed.rules().get(0).on().detail()).contains("heavy");
    }

    @Test
    void aSpelledOutBuiltInTriggerCollapsesToItsBareFiringName() {
        ParsedRules parsed = compile(List.of(Map.of(
                "on", "materione:click:right", "do", List.of(Map.of("message", "x")))));

        assertThat(parsed.rules().get(0).on().type()).isEqualTo("click");
        assertThat(parsed.rules().get(0).on().matches("click", "right")).isTrue();
    }

    @Test
    void anEmptyActionPayloadReachesTheTypeAsNoParameters() {
        java.util.Map<String, Object> declaration = new java.util.LinkedHashMap<>();
        declaration.put("on", "click");
        declaration.put("do", List.of(java.util.Collections.singletonMap("cancel", null)));
        ParsedRules parsed = compile(List.of(declaration));

        assertThat(parsed.problems()).isEmpty();
        assertThat(((ActionStep<?>) parsed.rules().get(0).steps().get(0)).params()).isEqualTo(true);
    }

    private static List<String> ids(Rule rule) {
        return rule.steps().stream().map(step -> ((ActionStep<?>) step).id()).toList();
    }

    /** An action that only takes a mapping. */
    private static final class FlagOnly implements dev.materione.api.logic.ActionType<String> {
        @Override
        public String id() {
            return "flag_only";
        }

        @Override
        public dev.materione.api.logic.ActionDescription describe() {
            return dev.materione.api.logic.ActionDescription.of(
                    dev.materione.api.registry.SelfDescription.of("Flag", "Takes a mapping"),
                    dev.materione.api.logic.LogicCategory.DATA);
        }

        @Override
        public Threading threading() {
            return Threading.ANY;
        }

        @Override
        public String parse(dev.materione.api.logic.ParamReader params) {
            return "";
        }

        @Override
        public dev.materione.api.logic.ActionOutcome execute(String params,
                dev.materione.api.logic.LogicContext context) {
            return dev.materione.api.logic.ActionOutcome.OK;
        }
    }

    /** A trigger that is only a name. */
    private static final class StormTrigger implements dev.materione.api.logic.TriggerType {
        @Override
        public String id() {
            return "storm";
        }

        @Override
        public dev.materione.api.logic.TriggerDescription describe() {
            return new dev.materione.api.logic.TriggerDescription(
                    dev.materione.api.registry.SelfDescription.of("Storm", "A storm"),
                    List.of("heavy"), List.of(), List.of(), List.of(), false);
        }
    }
}
