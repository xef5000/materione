package dev.materione.behavior.rule;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rule parsing is pure and fail-soft: broken rules become problems, siblings load. */
class RuleParserTest {

    private final NamespacedId owner = NamespacedId.of("sample", "wand");

    @Test
    void parsesACompleteRule() {
        ParsedRules parsed = RuleParser.compile(owner, List.of(Map.of(
                "id", "boom",
                "on", "click:right",
                "if", List.of("player.sneaking == false"),
                "do", List.of(
                        Map.of("run_command", "say hi", "chance", 0.5, "delay", 10),
                        Map.of("message", Map.of("text", "&aDone", "target", "actionbar"))),
                "cooldown", 1000,
                "charges", 3,
                "per", "global")));

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.rules()).hasSize(1);
        Rule rule = parsed.rules().get(0);
        assertThat(rule.id()).isEqualTo("boom");
        assertThat(rule.on().type()).isEqualTo("click");
        assertThat(rule.on().detail()).contains("right");
        assertThat(rule.conditions()).hasSize(1);
        assertThat(rule.actions()).hasSize(2);

        ActionSpec first = rule.actions().get(0);
        assertThat(first.type()).isEqualTo("run_command");
        assertThat(first.scalarParam()).isEqualTo("say hi");
        assertThat(first.chance()).isEqualTo(0.5);
        assertThat(first.delayTicks()).isEqualTo(10);

        ActionSpec second = rule.actions().get(1);
        assertThat(second.type()).isEqualTo("message");
        assertThat(second.param("target")).isEqualTo("actionbar");

        assertThat(rule.options().cooldownMs()).isEqualTo(1000);
        assertThat(rule.options().charges()).isEqualTo(3);
        assertThat(rule.options().scope()).isEqualTo(RuleOptions.PER_GLOBAL);
    }

    @Test
    void defaultsApplyWhenOptionsAreAbsent() {
        ParsedRules parsed = RuleParser.compile(owner, List.of(Map.of(
                "on", "consume",
                "do", List.of(Map.of("sound", "minecraft:entity.player.levelup")))));

        Rule rule = parsed.rules().get(0);
        assertThat(rule.id()).contains("#"); // fallback identity <owner>#<index>
        assertThat(rule.on().detail()).isEmpty();
        assertThat(rule.actions().get(0).chance()).isEqualTo(1.0);
        assertThat(rule.options()).isEqualTo(RuleOptions.none());
    }

    @Test
    void aSingleConditionStringIsAcceptedListForm() {
        ParsedRules parsed = RuleParser.compile(owner, List.of(Map.of(
                "on", "drop",
                "if", "item.amount > 1",
                "do", List.of(Map.of("cancel", true)))));
        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.rules().get(0).conditions()).hasSize(1);
    }

    @Test
    void brokenRulesAreProblemsAndSiblingsStillLoad() {
        ParsedRules parsed = RuleParser.compile(owner, List.of(
                "not-a-mapping",
                Map.of("if", List.of("true"), "do", List.of()),                 // missing on:
                Map.of("on", "clik", "do", List.of(Map.of("cancel", true))),    // typo'd event
                Map.of("on", "click", "if", List.of("1 +"), "do",
                        List.of(Map.of("cancel", true))),                       // bad expression
                Map.of("on", "click", "do", List.of()),                         // no actions
                Map.of("on", "click", "do",
                        List.of(Map.of("chance", 2))),                          // action without type key
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
        assertThat(RuleParser.compile(owner, null).isEmpty()).isTrue();
        assertThat(RuleParser.compile(owner, "behavior").isEmpty()).isTrue();
        assertThat(RuleParser.compile(owner, List.of()).isEmpty()).isTrue();
    }

    @Test
    void selectorMatchingHonoursDetailWildcards() {
        var any = new EventSelector("click", java.util.Optional.empty());
        var right = new EventSelector("click", java.util.Optional.of("right"));
        assertThat(any.matches("click", "left")).isTrue();
        assertThat(right.matches("click", "left")).isFalse();
        assertThat(right.matches("break", "")).isFalse();
    }
}
