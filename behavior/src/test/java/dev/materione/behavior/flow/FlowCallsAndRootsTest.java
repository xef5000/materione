package dev.materione.behavior.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.rule.ParsedRules;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Named flows (logic.md §2.4): linking, cycles, depth, arguments; and the {@code ${root}} check (§2.6). */
class FlowCallsAndRootsTest {

    private final FlowFixture f = new FlowFixture();

    private static List<String> codes(ParsedRules parsed) {
        return parsed.problems().stream().map(problem -> problem.code().code()).toList();
    }

    // --- linking -------------------------------------------------------------------

    @Test
    void aCallToAnUnknownFlowIsAnErrorThatSkipsTheRule() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do:
                    - a: x
                    - call: ghost
                - on: click
                  do: [ { a: survivor } ]
                """, "real: [ { a: y } ]");

        assertThat(parsed.rules()).hasSize(1);
        assertThat(parsed.rules().get(0).path()).hasToString("behavior.1");
        assertThat(parsed.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0058");
            assertThat(problem.severity()).isEqualTo(Severity.ERROR);
            assertThat(problem.message()).contains("flow 'ghost'");
            assertThat(problem.hint()).contains("[real]");
            assertThat(problem.target().field()).containsExactly("behavior", "0", "do", "1");
        });
    }

    @Test
    void aCallInAResourceWithNoFlowsExplainsFlowsAreDeclaredUnderFlows() {
        ParsedRules parsed = f.compile("- on: click\n  do: [ { call: other:thing } ]\n");

        assertThat(parsed.rules()).isEmpty();
        assertThat(parsed.problems().get(0).hint()).contains("declare it under flows:");
    }

    @Test
    void aFlowCallingItselfIsACycle() {
        ParsedRules parsed = f.compile("- on: click\n  do: [ { call: loop } ]\n", "loop: [ { a: x }, { call: loop } ]");

        assertThat(parsed.rules()).isEmpty();
        assertThat(codes(parsed)).contains("MTR-0058");
        assertThat(parsed.problems().get(0).message()).contains("call cycle: loop -> loop");
        assertThat(parsed.problems().get(0).target().field()).containsExactly("flows", "loop", "1");
    }

    @Test
    void aCycleThroughOtherFlowsIsNamedInFull() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do: [ { call: one } ]
                - on: click
                  do: [ { a: survivor } ]
                """, """
                one: [ { call: two } ]
                two: [ { call: three } ]
                three: [ { call: one } ]
                """);

        assertThat(parsed.rules()).hasSize(1);
        assertThat(parsed.problems().get(0).message()).contains("one -> two -> three -> one");
        assertThat(parsed.problems()).allSatisfy(problem -> assertThat(problem.code().code()).isEqualTo("MTR-0058"));
    }

    @Test
    void aFlowThatCannotLoadSkipsEveryRuleThatCallsIt() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do: [ { call: bad } ]
                - on: click
                  do: [ { call: bad } ]
                """, "bad: [ { nope: 1 } ]");

        assertThat(parsed.rules()).isEmpty();
        assertThat(codes(parsed)).containsExactly("MTR-0057", "MTR-0058", "MTR-0058");
        assertThat(parsed.problems().get(2).message()).contains("which did not load");
    }

    @Test
    void aFlowDeclaredButNeverCalledStillHasItsProblemsReported() {
        ParsedRules parsed = f.compile("- on: click\n  do: [ { a: x } ]\n", "orphan: [ { a: ok }, { nope: 1 } ]");

        assertThat(parsed.rules()).hasSize(1);
        assertThat(parsed.problems()).singleElement().satisfies(problem ->
                assertThat(problem.target().field()).containsExactly("flows", "orphan", "1"));
    }

    @Test
    void flowsThatAreNotAMappingOrHaveOddIdsAreDiagnosedNotFatal() {
        ParsedRules notMap = f.compile("- on: click\n  do: [ { a: x } ]\n", "[ 1, 2 ]");
        assertThat(notMap.rules()).hasSize(1);
        assertThat(notMap.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0021");
            assertThat(problem.target().field()).containsExactly("flows");
        });

        ParsedRules oddId = f.compile("- on: click\n  do: [ { a: x } ]\n", "\"bad id\": [ { a: x } ]");
        assertThat(oddId.problems()).singleElement().satisfies(problem ->
                assertThat(problem.message()).contains("flow id 'bad id'"));
    }

    // --- depth ---------------------------------------------------------------------

    private static String chain(int length) {
        return IntStream.range(0, length)
                .mapToObj(i -> "f" + i + ": [ " + (i + 1 < length ? "{ call: f" + (i + 1) + " }" : "{ a: end }") + " ]")
                .collect(Collectors.joining("\n"));
    }

    @Test
    void aChainOfSixteenNestedCallsLoadsAndSeventeenIsRejected() {
        ParsedRules ok = f.compile("- on: click\n  do: [ { call: f0 } ]\n", chain(16));
        assertThat(ok.problems()).isEmpty();
        assertThat(ok.rules()).hasSize(1);

        ParsedRules tooDeep = f.compile("- on: click\n  do: [ { call: f0 } ]\n", chain(17));
        assertThat(tooDeep.rules()).isEmpty();
        assertThat(tooDeep.problems()).anySatisfy(problem -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0058");
            assertThat(problem.message()).contains("past the limit of 16");
        });
    }

    // --- arguments -----------------------------------------------------------------

    @Test
    void callArgumentsCompileToLiteralsTemplatesAndTypedExpressions() {
        var rule = f.clean("""
                - on: click
                  do:
                    - call:
                        flow: pay
                        with:
                          price: 250
                          label: "Sword for ${player.name}"
                          double: "${player.level}"
                          list: [ 1, 2 ]
                """, "pay: [ { say: \"${args.price} ${args.label}\" } ]");

        var args = ((CallStep) rule.steps().get(0)).args();
        assertThat(args.get("price")).isEqualTo(new ArgValue.Literal(250));
        assertThat(args.get("label")).isInstanceOf(ArgValue.Text.class);
        assertThat(args.get("double")).isInstanceOf(ArgValue.Typed.class);
        assertThat(((ArgValue.Literal) args.get("list")).value()).isEqualTo(List.of(1, 2));
        assertThat(args).containsOnlyKeys("price", "label", "double", "list"); // authored order kept
        assertThat(List.copyOf(args.keySet())).containsExactly("price", "label", "double", "list");
    }

    @Test
    void badArgumentsAreDiagnosedAtTheirPath() {
        ParsedRules badName = f.compile(
                "- on: click\n  do: [ { call: { flow: p, with: { \"a b\": 1 } } } ]\n", "p: [ { a: x } ]");
        assertThat(badName.rules()).isEmpty();
        assertThat(badName.problems().get(0).target().field()).containsExactly("behavior", "0", "do", "0", "with", "a b");

        ParsedRules badTemplate = f.compile(
                "- on: click\n  do: [ { call: { flow: p, with: { n: \"${1 +}\" } } } ]\n", "p: [ { a: x } ]");
        assertThat(badTemplate.rules()).isEmpty(); // its only step could not load
        assertThat(codes(badTemplate)).contains("MTR-0022");

        ParsedRules notMap = f.compile("- on: click\n  do: [ { call: { flow: p, with: 5 } } ]\n", "p: [ { a: x } ]");
        assertThat(notMap.rules()).isEmpty();
        assertThat(codes(notMap)).containsExactly("MTR-0021");
    }

    // --- ${root} checking ----------------------------------------------------------

    @Test
    void anUnknownRootInATemplateIsAWarningNotAFailure() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do:
                    - say: "hello ${plyer.name} and ${player.name}"
                """);

        assertThat(parsed.rules()).hasSize(1); // the step still loads
        assertThat(parsed.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0060");
            assertThat(problem.severity()).isEqualTo(Severity.WARN);
            assertThat(problem.message()).contains("'plyer'").contains("'click' trigger");
            assertThat(problem.hint()).contains("known roots:").contains("player");
            assertThat(problem.target().field()).containsExactly("behavior", "0", "do", "0");
        });
    }

    @Test
    void conditionsAndCallArgumentsAreCheckedToo() {
        ParsedRules parsed = f.compile("""
                - on: click
                  if: "itme.amount > 1"
                  do:
                    - if: "wrld.time > 1"
                      then: [ { a: x } ]
                    - call: { flow: p, with: { n: "${sever.online}" } }
                """, "p: [ { a: x } ]");

        assertThat(parsed.rules()).hasSize(1);
        assertThat(codes(parsed)).containsExactly("MTR-0060", "MTR-0060", "MTR-0060");
        assertThat(parsed.problems().stream().map(problem -> String.join(".", problem.target().field())))
                .containsExactly("behavior.0.if", "behavior.0.do.0.if", "behavior.0.do.1.with.n");
    }

    @Test
    void knownRootsPassTheTriggerSchemaTheUniversalRootsAndLocals() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do:
                    - say: "${item.amount} ${player.name} ${server.online} ${vars.count} ${location.x}"
                """);

        assertThat(parsed.problems()).isEmpty();
    }

    @Test
    void argsIsKnownInsideAFlowButNotInARuleBody() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do:
                    - say: "${args.n}"
                    - call: p
                """, "p: [ { say: \"${args.n}\" } ]");

        assertThat(codes(parsed)).containsExactly("MTR-0060");
        assertThat(parsed.problems().get(0).target().field()).containsExactly("behavior", "0", "do", "0");
    }

    @Test
    void aFlowIsCheckedAgainstTheUnionOfItsResourcesTriggersAndOnlyOnce() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do: [ { call: shared } ]
                - on: break
                  do: [ { call: shared } ]
                """, "shared: [ { say: \"${block.id} ${item.id} ${typo.x} ${typo.y}\" } ]");

        assertThat(parsed.rules()).hasSize(2);
        // block.* comes from the break trigger, item.* from click: both are fine; typo is warned once
        assertThat(parsed.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0060");
            assertThat(problem.target().field()).containsExactly("flows", "shared", "0");
        });
    }

    @Test
    void functionCallsInExpressionsAndTemplatesAreBoundAndTheirArgumentsAreRootChecked() {
        ExpressionFunction twice = new ExpressionFunction() {
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
        f.logic.registries().registry(RegistryKeys.EXPRESSION_FUNCTIONS, ExpressionFunction.class)
                .register(NamespacedId.of("materione", "twice"), twice, twice.describe().self(), "test");

        ParsedRules good = f.compile("""
                - on: click
                  if: "twice(player.level) > 10"
                  do:
                    - say: "double ${twice(player.level)}"
                """);
        assertThat(good.problems()).isEmpty();
        var context = new dev.materione.behavior.logic.MapLogicContext(
                java.util.Map.of("player", java.util.Map.of("level", 6)));
        assertThat(good.rules().get(0).condition().test(context)).isTrue();

        ParsedRules typo = f.compile("""
                - on: click
                  if: "twice(plyer.level) > 10"
                  do:
                    - say: "double ${twice(wrld.time)}"
                """);
        assertThat(codes(typo)).containsExactly("MTR-0060", "MTR-0060");
        assertThat(typo.problems().get(0).message()).contains("'plyer'");
        assertThat(typo.problems().get(1).message()).contains("'wrld'");
    }

    @Test
    void aTriggerThatDeclaresNoContextIsNotChecked() {
        f.logic.registries().registry(RegistryKeys.TRIGGERS, TriggerType.class).register(
                NamespacedId.of("example", "storm"), new Storm(), SelfDescription.of("Storm", "A storm"), "example");

        ParsedRules parsed = f.compile("""
                - on: example:storm
                  do: [ { say: "${anything.at_all}" } ]
                """);

        assertThat(parsed.problems()).isEmpty();
    }

    /** A trigger with no context schema. */
    private static final class Storm implements TriggerType {
        @Override
        public String id() {
            return "storm";
        }

        @Override
        public TriggerDescription describe() {
            return new TriggerDescription(SelfDescription.of("Storm", "A storm"),
                    List.of(), List.of(), List.of(), List.of(), false);
        }
    }

    @Test
    void generatedRulesNameThemselvesButPointAtNoField() {
        ParsedRules parsed = FlowCompiler.compileGenerated(FlowFixture.OWNER, FlowFixture.yaml("""
                - on: click
                  id: sample:shop/mechanic/x/0/0
                  do: [ { nope: 1 }, { a: x } ]
                """), f.logic.lookup());

        assertThat(parsed.rules()).hasSize(1);
        assertThat(parsed.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.target()).isNull();
            assertThat(problem.message()).contains("sample:shop/mechanic/x/0/0");
        });
    }
}
