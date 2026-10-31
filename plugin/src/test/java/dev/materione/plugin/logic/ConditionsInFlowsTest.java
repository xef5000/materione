package dev.materione.plugin.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.behavior.flow.ChooseStep;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.flow.IfStep;
import dev.materione.behavior.flow.Step;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.logic.action.PlayerFake;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The built-in conditions inside real flows (logic.md §2.3, §16): compiled from authored rules through the live
 * lookup, then tested against a fake player. Covers a rule {@code if:}, a step {@code if:}, a {@code choose} branch,
 * an {@code at_least} and an expression function call. The interpreter is not run here; the condition trees are.
 */
class ConditionsInFlowsTest {

    private static final NamespacedId OWNER = NamespacedId.of("probe", "item");

    private final RegistryService registries = new RegistryService();
    private final LogicService service = LogicTestSupport.withBuiltIns(registries);
    private final PlayerFake fake = new PlayerFake();

    /** A YAML-shaped mapping, keys in order. */
    private static Map<String, Object> map(Object... keyValues) {
        var map = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private ParsedRules compile(Map<String, Object> rule) {
        var behavior = new ArrayList<Object>();
        behavior.add(rule);
        return FlowCompiler.compile(OWNER, behavior, service.lookup());
    }

    @Test
    void aConditionIsAcceptedAsARuleLevelIfWithAnExpressionFunctionBesideIt() {
        fake.permissions.add("probe.fly");
        var parsed = compile(map("on", "click:right",
                "if", List.of(map("has_permission", "probe.fly"), "${contains(lower(player.name), 'ale')}"),
                "do", List.of(map("message", "hi"))));

        assertThat(parsed.problems()).isEmpty();
        Rule rule = parsed.rules().get(0);
        assertThat(rule.condition().test(fake.context())).isTrue();

        fake.permissions.clear();
        assertThat(rule.condition().test(fake.context())).isFalse();
    }

    @Test
    void aConditionIsAcceptedAsAStepIfWithItsThenAndElseBranches() {
        fake.permissions.add("probe.fly");
        var parsed = compile(map("on", "click:right", "do", List.of(
                map("if", map("has_permission", "probe.fly"),
                        "then", List.of(map("message", "yes")),
                        "else", List.of(map("message", "no"))))));

        assertThat(parsed.problems()).isEmpty();
        var step = (IfStep) parsed.rules().get(0).steps().get(0);
        assertThat(step.condition().test(fake.context())).isTrue();
        fake.permissions.clear();
        assertThat(step.condition().test(fake.context())).isFalse();
    }

    @Test
    void conditionsAreTheTestsOfEachChooseBranch() {
        var parsed = compile(map("on", "click:right", "do", List.of(
                map("choose", List.of(
                                map("if", map("in_world", "nether"), "then", List.of(map("message", "nether"))),
                                map("if", map("has_exp", map("amount", 1, "unit", "levels")),
                                        "then", List.of(map("message", "levelled")))),
                        "else", List.of(map("message", "none"))))));

        assertThat(parsed.problems()).isEmpty();
        var choose = (ChooseStep) parsed.rules().get(0).steps().get(0);
        assertThat(choose.cases().get(0).condition().test(fake.context())).isFalse();
        assertThat(choose.cases().get(1).condition().test(fake.context())).isFalse();

        fake.level = 1;
        assertThat(choose.cases().get(1).condition().test(fake.context())).isTrue();
        fake.worldName = "nether";
        assertThat(choose.cases().get(0).condition().test(fake.context())).isTrue();
    }

    @Test
    void aConditionIsCountedInsideAtLeast() {
        fake.permissions.add("probe.fly");
        // in_gui is false here (no GUI port is wired in this test), so at least two of the two cannot hold
        var parsed = compile(map("on", "click:right", "do", List.of(
                map("if", map("at_least", map("count", 2, "of",
                                List.of(map("has_permission", "probe.fly"), map("in_gui", "shop:menu")))),
                        "then", List.of(map("message", "both"))),
                map("if", map("at_least", map("count", 1, "of",
                                List.of(map("has_permission", "probe.fly"), map("in_gui", "shop:menu")))),
                        "then", List.of(map("message", "one"))))));

        assertThat(parsed.problems()).isEmpty();
        List<Step> steps = parsed.rules().get(0).steps();
        assertThat(((IfStep) steps.get(0)).condition().test(fake.context())).isFalse();
        assertThat(((IfStep) steps.get(1)).condition().test(fake.context())).isTrue();
    }

    @Test
    void aReservedCompositeNameIsAGroupNotAPredicate() {
        fake.permissions.add("probe.fly");
        var parsed = compile(map("on", "click:right",
                "if", map("all", List.of(map("has_permission", "probe.fly"), map("in_world", "world"))),
                "do", List.of(map("message", "hi"))));

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.rules().get(0).condition().test(fake.context())).isTrue();
    }

    @Test
    void aMalformedBuiltInConditionIsReportedAndOnlyItsStepIsSkipped() {
        var parsed = compile(map("on", "click:right", "do", List.of(
                map("if", map("has_money", "lots"), "then", List.of(map("message", "rich"))),
                map("message", "still here"))));

        assertThat(parsed.problems()).isNotEmpty();
        assertThat(parsed.rules().get(0).steps()).hasSize(1);
    }
}
