package dev.materione.behavior.logic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ContextFacet;
import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.expr.FunctionResolver;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.RuleParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Function calls reach the engine from both entry points: a template or expression compiled by
 * {@link EngineExpressions}, and a rule's {@code if:} and action parameters compiled by the
 * {@link RuleParser} against the live registry.
 */
class FunctionBindingTest {

    /** A function that doubles a number and reads the run's facet, so the logic context is proven to arrive. */
    private static final ContextFacet<String> SUFFIX = ContextFacet.of("test:suffix", String.class);

    private static final ExpressionFunction TWICE = new ExpressionFunction() {
        @Override
        public String id() {
            return "twice";
        }

        @Override
        public FunctionDescription describe() {
            return FunctionDescription.of(new SelfDescription("Twice", "Doubles a number",
                    List.of(ParameterSchema.of("n", ParameterType.DECIMAL, true)), "1.0.0"), ParameterType.DECIMAL);
        }

        @Override
        public Object call(List<Object> arguments, LogicContext context) {
            return ((Number) arguments.get(0)).doubleValue() * 2;
        }
    };

    private static final ExpressionFunction TAGGED = new ExpressionFunction() {
        @Override
        public String id() {
            return "tagged";
        }

        @Override
        public FunctionDescription describe() {
            return FunctionDescription.of(new SelfDescription("Tagged", "Appends the run's suffix",
                    List.of(ParameterSchema.of("text", ParameterType.STRING, true)), "1.0.0"), ParameterType.STRING);
        }

        @Override
        public Object call(List<Object> arguments, LogicContext context) {
            return arguments.get(0) + context.facet(SUFFIX).orElse("?");
        }
    };

    private static final FunctionResolver FUNCTIONS = name -> switch (name) {
        case "twice" -> Optional.of(TWICE);
        case "tagged" -> Optional.of(TAGGED);
        default -> Optional.empty();
    };

    private static MapLogicContext run() {
        return new MapLogicContext(Map.of("player", Map.of("level", 4d))).with(SUFFIX, "!");
    }

    @Test
    void aTemplateCallsAFunctionAndSeesTheRunsFacets() {
        var template = EngineExpressions.with(FUNCTIONS).template("L=${twice(player.level)} ${tagged('hi')}");

        assertThat(template.render(run())).isEqualTo("L=8 hi!");
    }

    @Test
    void anExpressionCallsAFunctionAndReturnsATypedValue() {
        var expression = EngineExpressions.with(FUNCTIONS).expression("twice(player.level) + 1");

        assertThat(expression.evaluate(run())).isEqualTo(9d);
    }

    @Test
    void aTemplateWithAnUnknownFunctionIsRejectedAtCompileTime() {
        assertThatThrownBy(() -> EngineExpressions.with(FUNCTIONS).template("${nope(1)}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown function 'nope'");
        assertThatThrownBy(() -> EngineExpressions.INSTANCE.template("${twice(1)}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown function 'twice'");
    }

    @Test
    void anExpressionWithAWrongArgumentCountIsRejectedAtCompileTime() {
        assertThatThrownBy(() -> EngineExpressions.with(FUNCTIONS).expression("twice(1, 2)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("takes 1 argument(s), got 2");
    }

    @Test
    void actionParametersBindCallsThroughTheParamReader() {
        var reader = ParamReader.of(Map.of("text", "${tagged('a')}"), EngineExpressions.with(FUNCTIONS));

        assertThat(reader.template("text").render(run())).isEqualTo("a!");
    }

    @Test
    void aRuleConditionResolvesFunctionsAgainstTheLiveRegistry() {
        var fixture = new LogicFixture()
                .action(LogicFixture.recording("run_command", Threading.GLOBAL, new ArrayList<>()));
        fixture.registries().registry(RegistryKeys.EXPRESSION_FUNCTIONS, ExpressionFunction.class)
                .register(NamespacedId.of("materione", "twice"), TWICE, TWICE.describe().self(), "test");
        var owner = NamespacedId.of("sample", "wand");

        ParsedRules parsed = RuleParser.compile(owner, List.of(Map.of(
                "on", "click",
                "if", List.of("twice(player.level) == 8"),
                "do", List.of(Map.of("run_command", "say hi")))), fixture.lookup());

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.rules()).hasSize(1);
        assertThat(parsed.rules().get(0).conditions()).hasSize(1);
    }

    @Test
    void aRuleWhoseConditionCallsAnUnknownFunctionIsSkippedWithTheExpressionDiagnostic() {
        var fixture = new LogicFixture();
        var owner = NamespacedId.of("sample", "wand");

        ParsedRules parsed = RuleParser.compile(owner, List.of(Map.of(
                "on", "click",
                "if", List.of("nosuch(player.level)"),
                "do", List.of(Map.of("run_command", "say hi")))), fixture.lookup());

        assertThat(parsed.rules()).isEmpty();
        assertThat(parsed.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0022");
            assertThat(problem.message()).contains("unknown function 'nosuch'");
        });
    }
}
