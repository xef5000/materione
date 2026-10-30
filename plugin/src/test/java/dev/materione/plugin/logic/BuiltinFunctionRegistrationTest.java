package dev.materione.plugin.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.BehaviorVocabulary;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.RuleParser;
import dev.materione.core.registry.RegistryService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The built-in functions as the rest of the system sees them: registered through the public
 * {@code api.logic().functions()}, listed in the vocabulary, and bound by the rule parser from a
 * rule's {@code if:} list.
 */
class BuiltinFunctionRegistrationTest {

    private final RegistryService registries = new RegistryService();
    private final LogicService service = LogicTestSupport.withBuiltIns(registries);

    @Test
    void theBuiltInFunctionsAreRegisteredUnderMaterioneAndListedInTheVocabulary() {
        var functions = service.vocabulary().functions();

        assertThat(functions.stream().map(BehaviorVocabulary.Function::id).toList())
                .contains("contains", "matches", "is_player", "format_number")
                .hasSize(BuiltinLogic.functions().size());
        assertThat(functions.stream().filter(f -> f.id().equals("random")).findFirst())
                .hasValueSatisfying(random -> {
                    assertThat(random.description().self().displayName()).isEqualTo("Random");
                    assertThat(random.registeredBy()).isEqualTo("materione");
                });
        assertThat(service.functions().resolve("contains")).isPresent();
        assertThat(service.functions().get(NamespacedId.of("materione", "round")))
                .hasValueSatisfying(round -> assertThat(round.id()).isEqualTo("round"));
    }

    @Test
    void aRuleConditionCallsABuiltInFunctionAndLoads() {
        ParsedRules parsed = RuleParser.compile(NamespacedId.of("sample", "wand"), List.of(Map.of(
                "on", "click",
                "if", List.of("contains(player.name, 'Alex') && round(player.level / 2) >= 1"),
                "do", List.of(Map.of("message", "hi")))), service.lookup());

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.rules()).hasSize(1);
    }

    @Test
    void aRuleCallingAnUnregisteredFunctionIsSkippedAtLoad() {
        ParsedRules parsed = RuleParser.compile(NamespacedId.of("sample", "wand"), List.of(Map.of(
                "on", "click",
                "if", List.of("sqrt(player.level) > 2"),
                "do", List.of(Map.of("message", "hi")))), service.lookup());

        assertThat(parsed.rules()).isEmpty();
        assertThat(parsed.problems()).singleElement()
                .satisfies(problem -> assertThat(problem.message()).contains("unknown function 'sqrt'"));
    }
}
