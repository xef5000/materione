package dev.materione.behavior.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.logic.LogicFixture;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.rule.FilterFacts;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.RuleFilters;
import dev.materione.behavior.run.FlowInterpreter;
import dev.materione.behavior.run.ManualScheduler;
import dev.materione.behavior.run.RunBudget;
import dev.materione.behavior.runtime.BehaviorEvent;
import dev.materione.behavior.runtime.CooldownStore;
import dev.materione.behavior.runtime.RuleEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Trigger filters (logic.md §4.3, §6.2): the keys a trigger declares are read off the rule, typed by
 * the trigger's own schema, and compared with what the firing site says about the event.
 */
class TriggerFiltersTest {

    private final List<String> ran = new ArrayList<>();
    private final LogicFixture logic = new LogicFixture()
            .action(LogicFixture.recording("a", Threading.PLAYER_REGION, ran))
            .action(LogicFixture.recording("b", Threading.PLAYER_REGION, ran));
    private final RuleEngine engine = new RuleEngine(
            new FlowInterpreter(new ManualScheduler(), RunBudget.DEFAULT, diagnostic -> {}), new CooldownStore());

    TriggerFiltersTest() {
        var triggers = logic.registries().registry(RegistryKeys.TRIGGERS, TriggerType.class);
        var click = new TriggerType() {
            @Override
            public String id() {
                return "tap";
            }

            @Override
            public TriggerDescription describe() {
                return new TriggerDescription(SelfDescription.of("Tap", "A test trigger"), List.of(), List.of("gui"),
                        List.of(
                                new ParameterSchema("layer", ParameterType.STRING, false, null, "the layer", null, null),
                                new ParameterSchema("kind", ParameterType.ENUM, false, null, "the kind",
                                        List.of("left", "right", "shift_left"), null),
                                new ParameterSchema("every", ParameterType.INTEGER, false, null, "ticks", null, null)
                                        .withEditor(new ParameterSchema.EditorMetadata(
                                                "Every", "general", 0, false, "number", "", "20")
                                                .withRange(1.0, null, "ticks"))),
                        List.of(), false);
            }
        };
        triggers.register(NamespacedId.of("materione", "tap"), click, click.describe().self(), "test");
        var pulse = new TriggerType() {
            @Override
            public String id() {
                return "pulse";
            }

            @Override
            public TriggerDescription describe() {
                return new TriggerDescription(SelfDescription.of("Pulse", "A test trigger with a required filter"),
                        List.of(), List.of("gui"),
                        List.of(new ParameterSchema("every", ParameterType.INTEGER, true, null, "ticks", null, null)),
                        List.of(), false);
            }
        };
        triggers.register(NamespacedId.of("materione", "pulse"), pulse, pulse.describe().self(), "test");
    }

    @Test
    void aRequiredFilterMustBeWrittenOrTheRuleIsSkippedAtTheRulePath() {
        ParsedRules parsed = compile("- on: pulse\n  do:\n    - a: x\n");

        assertThat(parsed.rules()).isEmpty();
        var problem = parsed.problems().get(0);
        assertThat(problem.code()).isEqualTo(DiagnosticCodes.BEHAVIOR_RULE_INVALID);
        assertThat(problem.message()).contains("'Pulse' needs 'every:'");
        assertThat(problem.target().field()).containsExactly("behavior", "0");
        assertThat(compile("- on: pulse\n  every: 5\n  do:\n    - a: x\n").problems()).isEmpty();
    }

    private ParsedRules compile(String behavior) {
        return FlowCompiler.compile(FlowFixtureOwner.OWNER, FlowFixtureOwner.yaml(behavior), logic.lookup());
    }

    private Rule one(String behavior) {
        ParsedRules parsed = compile(behavior);
        assertThat(parsed.problems()).isEmpty();
        return parsed.rules().get(0);
    }

    @Test
    void aRuleReadsTheFiltersItsTriggerDeclares() {
        Rule rule = one("""
                - on: tap
                  layer: buy
                  kind: [LEFT, shift_left]
                  every: 20
                  do:
                    - a: x
                """);

        assertThat(rule.filters().get("layer")).contains(List.of("buy"));
        assertThat(rule.filters().get("kind")).contains(List.of("left", "shift_left")); // enum values are lower-cased
        assertThat(rule.filters().get("every")).contains(List.of("20"));
    }

    @Test
    void aKeyTheTriggerDoesNotDeclareIsNotAFilter() {
        Rule rule = one("""
                - on: tap
                  colour: red
                  do:
                    - a: x
                """);

        assertThat(rule.filters().isEmpty()).isTrue();
    }

    @Test
    void aBadEnumValueSkipsTheRuleAtTheKeyPath() {
        ParsedRules parsed = compile("""
                - on: tap
                  kind: double_tap
                  do:
                    - a: x
                """);

        assertThat(parsed.rules()).isEmpty();
        var problem = parsed.problems().get(0);
        assertThat(problem.code()).isEqualTo(DiagnosticCodes.BEHAVIOR_RULE_INVALID);
        assertThat(problem.message()).contains("unknown kind 'double_tap'");
        assertThat(problem.target().field()).containsExactly("behavior", "0", "kind");
        assertThat(problem.hint()).contains("left, right, shift_left");
    }

    @Test
    void anIntegerFilterMustBeAWholeNumberFromItsMinimum() {
        assertThat(compile("- on: tap\n  every: 0\n  do:\n    - a: x\n").problems().get(0).message())
                .contains("must be at least 1");
        assertThat(compile("- on: tap\n  every: 2.5\n  do:\n    - a: x\n").problems().get(0).message())
                .contains("must be a whole number");
        assertThat(compile("- on: tap\n  layer: []\n  do:\n    - a: x\n").problems().get(0).message())
                .contains("is empty");
    }

    @Test
    void theEngineFiresARuleOnlyWhenEveryFilterMatchesTheEventsFacts() {
        List<Rule> rules = compile("""
                - on: tap
                  layer: buy
                  kind: left
                  do:
                    - a: buy-left
                - on: tap
                  layer: [buy, sell]
                  do:
                    - a: either
                - on: tap
                  do:
                    - a: always
                """).rules();

        fire(rules, Map.of("layer", "buy", "kind", "left"));
        assertThat(ran).containsExactly("a:buy-left", "a:either", "a:always");

        ran.clear();
        fire(rules, Map.of("layer", "buy", "kind", "right"));
        assertThat(ran).containsExactly("a:either", "a:always");

        ran.clear();
        fire(rules, Map.of("layer", "SELL"));
        assertThat(ran).containsExactly("a:either", "a:always"); // ids compare case-insensitively

        ran.clear();
        fire(rules, Map.of());
        assertThat(ran).containsExactly("a:always"); // a filtered rule needs the fact to be present
    }

    @Test
    void filtersAreMatchedByTheRecordItself() {
        var filters = new RuleFilters(Map.of("slot", List.of("3", "4")));

        assertThat(filters.matches(FilterFacts.of(Map.of("slot", "4")))).isTrue();
        assertThat(filters.matches(FilterFacts.of(Map.of("slot", "5")))).isFalse();
        assertThat(filters.matches(FilterFacts.NONE)).isFalse();
        assertThat(RuleFilters.none().matches(FilterFacts.NONE)).isTrue();
    }

    private void fire(List<Rule> rules, Map<String, String> facts) {
        engine.fire(rules, new BehaviorEvent("tap", null, new MapLogicContext(Map.of()), null, "player-1")
                .withFilterFacts(FilterFacts.of(facts)));
    }

    /** The owner and YAML reader the flow fixture uses, shared without its action set. */
    private static final class FlowFixtureOwner {
        static final NamespacedId OWNER = FlowFixture.OWNER;

        static Object yaml(String text) {
            return FlowFixture.yaml(text);
        }
    }
}
