package dev.materione.behavior.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ContextFacet;
import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/** Call syntax, parse-time binding and the evaluation of calls (logic.md §4.2, runtime-systems §2.1). */
class ExpressionCallTest {

    /** A test function: a description and a body. */
    private record Stub(String id, FunctionDescription description, BiFunction<List<Object>, LogicContext, Object> body)
            implements ExpressionFunction {
        @Override
        public FunctionDescription describe() {
            return description;
        }

        @Override
        public Object call(List<Object> arguments, LogicContext context) {
            return body.apply(arguments, context);
        }
    }

    private static FunctionDescription described(int params, int optional, boolean variadic) {
        var schemas = new ArrayList<ParameterSchema>();
        for (int i = 0; i < params; i++) {
            boolean required = i < params - optional;
            schemas.add(new ParameterSchema("p" + i, ParameterType.DECIMAL, required, null, null, null, null));
        }
        return new FunctionDescription(new SelfDescription("F", "test", schemas, "1.0.0"),
                ParameterType.DECIMAL, variadic);
    }

    /** Sums its numeric arguments; at least one is required. */
    private static final ExpressionFunction SUM = new Stub("sum", described(1, 0, true), (args, ctx) -> {
        double total = 0;
        for (Object arg : args) {
            total += ((Number) arg).doubleValue();
        }
        return total;
    });

    private static final ExpressionFunction ADD = new Stub("add", described(2, 0, false),
            (args, ctx) -> ((Number) args.get(0)).doubleValue() + ((Number) args.get(1)).doubleValue());

    private static final ExpressionFunction NOW = new Stub("now", described(0, 0, false), (args, ctx) -> 7);

    private static final ExpressionFunction ECHO = new Stub("echo", described(1, 0, false), (args, ctx) -> args.get(0));

    private static final ExpressionFunction ROUND = new Stub("round", described(2, 1, false),
            (args, ctx) -> args.size());

    private static final ExpressionFunction EXPLODES = new Stub("boom", described(0, 0, false),
            (args, ctx) -> {
                throw new IllegalArgumentException("wrong kind");
            });

    private static final ExpressionFunction GREET = new Stub("greet", described(1, 0, false),
            (args, ctx) -> "hi " + args.get(0));

    /** The test registry: unqualified names are Materione's, {@code example:...} is an addon's. */
    private static final FunctionResolver FUNCTIONS = name -> switch (name) {
        case "sum" -> Optional.of(SUM);
        case "add" -> Optional.of(ADD);
        case "now" -> Optional.of(NOW);
        case "echo" -> Optional.of(ECHO);
        case "round" -> Optional.of(ROUND);
        case "boom" -> Optional.of(EXPLODES);
        case "example:greet" -> Optional.of(GREET);
        default -> Optional.empty();
    };

    private static Object eval(String source, Map<String, Object> root) {
        return ExpressionParser.parse(source, FUNCTIONS).eval(new EvaluationContext(root));
    }

    @Test
    void aCallBindsAgainstTheResolverAndEvaluatesItsArguments() {
        assertThat(eval("add(2, 3)", Map.of())).isEqualTo(5.0);
        assertThat(eval("add(player.level, 1)", Map.of("player", Map.of("level", 9d)))).isEqualTo(10.0);
        assertThat(eval("echo('hello')", Map.of())).isEqualTo("hello");
        assertThat(eval("echo(-4.5)", Map.of())).isEqualTo(-4.5);
        assertThat(eval("echo(true)", Map.of())).isEqualTo(true);
        assertThat(eval("echo(null)", Map.of())).isNull();
    }

    @Test
    void aZeroArgumentCallAndNestedCallsEvaluate() {
        assertThat(eval("now()", Map.of())).isEqualTo(7.0);
        assertThat(eval("add(sum(1, 2, 3), add(now(), 1))", Map.of())).isEqualTo(14.0);
        assertThat(eval("sum(7)", Map.of())).isEqualTo(7.0);
        assertThat(eval("echo(add(1, 1)) == 2", Map.of())).isEqualTo(true);
    }

    @Test
    void aCallIsAnOperandAndTakesATernaryArgument() {
        assertThat(eval("add(1, 2) * 10", Map.of())).isEqualTo(30.0);
        assertThat(eval("echo(1 > 0 ? 'yes' : 'no')", Map.of())).isEqualTo("yes");
        assertThat(eval("(add(1, 2))", Map.of())).isEqualTo(3.0);
    }

    @Test
    void anAddonFunctionIsCalledWithADottedQualifiedName() {
        assertThat(eval("example.greet('Alex')", Map.of())).isEqualTo("hi Alex");
    }

    @Test
    void aCallReceivesTheRunsLogicContext() {
        ContextFacet<String> tag = ContextFacet.of("test:tag", String.class);
        LogicContext run = new LogicContext() {
            @Override
            public <T> Optional<T> facet(ContextFacet<T> facet) {
                return facet.equals(tag) ? Optional.of(facet.cast("ruby")) : Optional.empty();
            }

            @Override
            public Object resolve(List<String> path) {
                return null;
            }

            @Override
            public Map<String, Object> locals() {
                return Map.of();
            }
        };
        var reads = new Stub("tag", described(0, 0, false), (args, ctx) -> ctx.facet(tag).orElse("none"));
        FunctionResolver withTag = name -> name.equals("tag") ? Optional.of(reads) : Optional.empty();

        Object result = ExpressionParser.parse("tag()", withTag).eval(new EvaluationContext(Map.of()).withLogic(run));

        assertThat(result).isEqualTo("ruby");
    }

    @Test
    void aFunctionThatFailsIsAnExpressionFailureNotACrash() {
        var expression = ExpressionParser.parse("boom()", FUNCTIONS);

        assertThatThrownBy(() -> expression.eval(new EvaluationContext(Map.of())))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("function 'boom' failed");
    }

    @Test
    void aNumberFromAFunctionIsNormalisedToADouble() {
        FunctionResolver integer = name -> Optional.of(new Stub("int", described(0, 0, false), (args, ctx) -> 3));

        assertThat(ExpressionParser.parse("int()", integer).eval(new EvaluationContext(Map.of())))
                .isEqualTo(3.0);
    }

    @Test
    void anOptionalParameterAcceptsTwoOrOneArguments() {
        assertThat(eval("round(2.5)", Map.of())).isEqualTo(1.0);
        assertThat(eval("round(2.5, 1)", Map.of())).isEqualTo(2.0);
    }

    @Test
    void unknownFunctionsAreParseErrorsWithAPosition() {
        assertThatThrownBy(() -> ExpressionParser.parse("nope(1)", FUNCTIONS))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("unknown function 'nope' at position 1");
        assertThatThrownBy(() -> ExpressionParser.parse("1 + add(2, nope())", FUNCTIONS))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("unknown function 'nope' at position 12");
        assertThatThrownBy(() -> ExpressionParser.parse("example.missing(1)", FUNCTIONS))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("unknown function 'example.missing'");
        assertThatThrownBy(() -> ExpressionParser.parse("a.b.c(1)", FUNCTIONS))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("unknown function 'a.b.c'");
    }

    @Test
    void withNoRegistryEveryCallIsUnknown() {
        assertThatThrownBy(() -> ExpressionParser.parse("add(1, 2)"))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("unknown function 'add'");
        assertThatThrownBy(() -> ExpressionParser.parse("add(1, 2)", FunctionResolver.NONE))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    void theArgumentCountIsCheckedAgainstTheDescription() {
        assertThatThrownBy(() -> ExpressionParser.parse("add(1)", FUNCTIONS))
                .isInstanceOf(ExpressionException.class)
                .hasMessageContaining("'add' takes 2 argument(s), got 1 at position 1");
        assertThatThrownBy(() -> ExpressionParser.parse("add(1, 2, 3)", FUNCTIONS))
                .hasMessageContaining("takes 2 argument(s), got 3");
        assertThatThrownBy(() -> ExpressionParser.parse("now(1)", FUNCTIONS))
                .hasMessageContaining("takes 0 argument(s), got 1");
        assertThatThrownBy(() -> ExpressionParser.parse("round()", FUNCTIONS))
                .hasMessageContaining("takes 1 to 2 argument(s), got 0");
        assertThatCode(() -> ExpressionParser.parse("sum(1, 2, 3, 4, 5)", FUNCTIONS)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ExpressionParser.parse("sum()", FUNCTIONS))
                .hasMessageContaining("takes at least 1 argument(s), got 0");
    }

    @Test
    void theSyntaxOfACallIsStrict() {
        assertThatThrownBy(() -> ExpressionParser.parse("add(1 2)", FUNCTIONS)).isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("add(1,)", FUNCTIONS)).isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("add(1, 2", FUNCTIONS)).isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("add(1, 2))", FUNCTIONS)).isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("1, 2", FUNCTIONS)).isInstanceOf(ExpressionException.class);
        assertThatCode(() -> ExpressionParser.parse("add(1, 2.)", FUNCTIONS)).doesNotThrowAnyException();
    }
}
