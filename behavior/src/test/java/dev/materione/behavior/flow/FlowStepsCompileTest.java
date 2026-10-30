package dev.materione.behavior.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The step grammar of logic.md §2.2 compiles to the sealed tree, with exact paths and fail-soft edges. */
class FlowStepsCompileTest {

    private final FlowFixture f = new FlowFixture();

    private static List<String> targets(ParsedRules parsed) {
        return parsed.problems().stream()
                .map(problem -> problem.target() == null ? "-" : String.join(".", problem.target().field()))
                .toList();
    }

    private static List<String> codes(ParsedRules parsed) {
        return parsed.problems().stream().map(problem -> problem.code().code()).toList();
    }

    // --- the tree and its paths ------------------------------------------------------

    @Test
    void everyStepKindCompilesAndEveryStepKnowsItsExactPath() {
        Rule rule = f.clean("""
                - on: click
                  do:
                    - a: one
                    - if: "player.level > 3"
                      then:
                        - b: two
                        - choose:
                            - if: { pass: x }
                              then: [ { c: three } ]
                          else:
                            - pure: four
                      else:
                        - stop
                    - require:
                        - a: five
                      else:
                        - b: six
                    - wait: 20
                    - call: tail
                """, "tail: [ { c: seven } ]");

        assertThat(rule.path()).hasToString("behavior.0");
        assertThat(rule.steps()).extracting(Step::kind)
                .containsExactly("action", "if", "require", "wait", "call");
        assertThat(rule.steps()).extracting(step -> step.path().toString()).containsExactly(
                "behavior.0.do.0", "behavior.0.do.1", "behavior.0.do.2", "behavior.0.do.3", "behavior.0.do.4");

        var branch = (IfStep) rule.steps().get(1);
        assertThat(branch.then().get(0).path()).hasToString("behavior.0.do.1.then.0");
        var choose = (ChooseStep) branch.then().get(1);
        assertThat(choose.path()).hasToString("behavior.0.do.1.then.1");
        assertThat(choose.cases().get(0).path()).hasToString("behavior.0.do.1.then.1.choose.0");
        assertThat(choose.cases().get(0).then().get(0).path())
                .hasToString("behavior.0.do.1.then.1.choose.0.then.0");
        assertThat(choose.otherwise().get(0).path()).hasToString("behavior.0.do.1.then.1.else.0");
        assertThat(branch.otherwise().get(0)).isInstanceOf(StopStep.class);
        assertThat(branch.otherwise().get(0).path()).hasToString("behavior.0.do.1.else.0");

        var require = (RequireStep) rule.steps().get(2);
        assertThat(require.steps().get(0).path()).hasToString("behavior.0.do.2.require.0");
        assertThat(require.otherwise().get(0).path()).hasToString("behavior.0.do.2.else.0");
        assertThat(require.blocked()).isFalse();

        var call = (CallStep) rule.steps().get(4);
        assertThat(call.flowId()).isEqualTo("tail");
        assertThat(call.flow().path()).hasToString("flows.tail");
        assertThat(call.flow().steps().get(0).path()).hasToString("flows.tail.0");
    }

    @Test
    void laterRulesCarryTheirOwnIndexInEveryPath() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do: [ { a: x } ]
                - on: break
                  do:
                    - if: "true"
                      then: [ { a: y } ]
                """);

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.rules().get(1).path()).hasToString("behavior.1");
        var branch = (IfStep) parsed.rules().get(1).steps().get(0);
        assertThat(branch.then().get(0).path()).hasToString("behavior.1.do.0.then.0");
    }

    @Test
    void aDiagnosticDeepInTheTreeCarriesTheExactPathAsItsTarget() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do:
                    - a: x
                    - if: "player.level > 3"
                      then:
                        - a: ok
                        - nope: bad
                """);

        assertThat(parsed.rules()).hasSize(1);
        assertThat(parsed.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0057");
            assertThat(problem.message()).contains("unknown action 'nope'").contains("behavior.0.do.1.then.1");
            assertThat(problem.target().field()).containsExactly("behavior", "0", "do", "1", "then", "1");
            assertThat(problem.resource()).isEqualTo(FlowFixture.OWNER);
        });
    }

    @Test
    void flowStepsAreReportedUnderTheirFlow() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do: [ { call: tail } ]
                """, "tail: [ { a: x }, { nope: y } ]");

        assertThat(parsed.rules()).hasSize(1); // a flow step lost does not cost the rule
        assertThat(targets(parsed)).containsExactly("flows.tail.1");
    }

    // --- the step shapes -------------------------------------------------------------

    @Test
    void waitStopAndCallAcceptEveryDocumentedSpelling() {
        Rule rule = f.clean("""
                - on: click
                  do:
                    - wait: 5
                    - wait: { ticks: 10 }
                    - stop
                    - stop: true
                    - stop: { cancel: true }
                    - stop: { cancel_session_runs: true }
                    - call: tail
                    - call: { flow: tail, with: { n: 1 } }
                    - call: tail
                      with: { n: 2 }
                """, "tail: [ { a: x } ]");

        assertThat(((WaitStep) rule.steps().get(0)).ticks()).isEqualTo(5);
        assertThat(((WaitStep) rule.steps().get(1)).ticks()).isEqualTo(10);
        assertThat(rule.steps().get(2)).isEqualTo(new StopStep(rule.steps().get(2).path(), false, false));
        assertThat(rule.steps().get(3)).isEqualTo(new StopStep(rule.steps().get(3).path(), false, false));
        assertThat(rule.steps().get(4)).isEqualTo(new StopStep(rule.steps().get(4).path(), true, false));
        assertThat(rule.steps().get(5)).isEqualTo(new StopStep(rule.steps().get(5).path(), false, true));
        assertThat(((CallStep) rule.steps().get(6)).args()).isEmpty();
        assertThat(((CallStep) rule.steps().get(7)).args()).containsOnlyKeys("n");
        assertThat(((CallStep) rule.steps().get(8)).args()).containsOnlyKeys("n");
    }

    @Test
    void aBareStringIsAnActionWithNoParametersOrStop() {
        Rule rule = f.clean("""
                - on: click
                  do:
                    - cancel
                    - stop
                """);

        assertThat(rule.steps().get(0)).isInstanceOf(ActionStep.class);
        assertThat(((ActionStep<?>) rule.steps().get(0)).id()).isEqualTo("cancel");
        assertThat(rule.steps().get(1)).isInstanceOf(StopStep.class);
    }

    @Test
    void aSingleStepMayBeWrittenWithoutTheList() {
        Rule rule = f.clean("""
                - on: click
                  do:
                    if: "true"
                    then: { a: x }
                """);

        assertThat(rule.steps()).singleElement().isInstanceOf(IfStep.class);
        assertThat(((IfStep) rule.steps().get(0)).then()).hasSize(1);
    }

    @Test
    void theEditorHintNodeIsAcceptedOnRulesAndStepsAndNeverStored() {
        Rule rule = f.clean("""
                - on: click
                  node: { x: 40, y: 80 }
                  do:
                    - a: x
                      node: { x: 1, y: 2 }
                    - if: "true"
                      node: { x: 3, y: 4 }
                      then: [ { b: y, node: { x: 5, y: 6 } } ]
                    - require: [ { c: z } ]
                      node: { x: 7, y: 8 }
                    - wait: 1
                      node: { x: 9, y: 9 }
                """);

        assertThat(rule.steps()).extracting(Step::kind).containsExactly("action", "if", "require", "wait");
    }

    @Test
    void compilingNeverAltersWhatTheAuthorWrote() {
        String text = """
                - on: click
                  node: { x: 40, y: 80 }
                  future_key: kept
                  do:
                    - a: x
                      node: { x: 1, y: 2 }
                    - call: { flow: tail, with: { n: 1 } }
                    - unknown_future_step_kind: { whatever: [1, 2] }
                """;
        String flows = "tail: [ { a: x } ]";
        Object behavior = FlowFixture.yaml(text);
        Object flowSection = FlowFixture.yaml(flows);

        dev.materione.behavior.flow.FlowCompiler.compile(FlowFixture.OWNER, behavior, flowSection, f.logic.lookup());

        assertThat(behavior).isEqualTo(FlowFixture.yaml(text));
        assertThat(flowSection).isEqualTo(FlowFixture.yaml(flows));
    }

    // --- fail-soft: what is skipped, and how much ------------------------------------

    @Test
    void anUnreadableStepSkipsTheRuleAndEverySiblingRuleStillLoads() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do: [ { wait: -1 } ]
                - on: click
                  do: [ { if: { pass: x } } ]
                - on: click
                  do: [ { a: x, wait: 5 } ]
                - on: click
                  do: [ { if: "true", then: [], chance: 0.5 } ]
                - on: click
                  do: [ 5 ]
                - on: click
                  do: [ { choose: [] } ]
                - on: click
                  do: [ { wait: 5, if: "true", then: [ { a: x } ] } ]
                - on: click
                  do: [ { a: survivor } ]
                """);

        assertThat(parsed.rules()).hasSize(1);
        assertThat(parsed.rules().get(0).path()).hasToString("behavior.7");
        assertThat(parsed.problems()).hasSize(7);
        assertThat(codes(parsed)).containsOnly("MTR-0021");
        assertThat(parsed.problems()).allSatisfy(problem -> {
            assertThat(problem.hint()).isNotBlank();
            assertThat(problem.target().field()).startsWith("behavior");
        });
    }

    @Test
    void anUnloadableStepDropsOnlyItselfInsideBranches() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do:
                    - if: "true"
                      then:
                        - nope: 1
                        - a: kept
                      else:
                        - b: kept
                """);

        var branch = (IfStep) parsed.rules().get(0).steps().get(0);
        assertThat(branch.then()).hasSize(1);
        assertThat(branch.otherwise()).hasSize(1);
        assertThat(codes(parsed)).containsExactly("MTR-0057");
    }

    @Test
    void aRequireThatLostAStepFailsClosedInsteadOfGuardingLess() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do:
                    - require:
                        - take_money_typo: 250
                        - a: give
                      else:
                        - b: refuse
                """);

        var require = (RequireStep) parsed.rules().get(0).steps().get(0);
        assertThat(require.blocked()).isTrue();
        assertThat(require.steps()).hasSize(1); // the survivor is kept, but the guard never lets it run
        assertThat(require.otherwise()).hasSize(1);
        assertThat(codes(parsed)).containsExactly("MTR-0057", "MTR-0057");
        assertThat(parsed.problems().get(1).message()).contains("fails every time");
        assertThat(parsed.problems().get(1).target().field()).containsExactly("behavior", "0", "do", "0");
    }

    @Test
    void aRequireAlsoFailsClosedWhenTheLostStepIsNestedInsideIt() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do:
                    - require:
                        - if: "true"
                          then:
                            - nope: 1
                """);

        assertThat(((RequireStep) parsed.rules().get(0).steps().get(0)).blocked()).isTrue();
    }

    @Test
    void aRequireWithEverythingLoadedIsNotBlocked() {
        assertThat(((RequireStep) f.clean(FlowFixture.rule("    - require: [ { a: x } ]\n")).steps().get(0)).blocked())
                .isFalse();
    }

    @Test
    void aChooseWithABadConditionIsDroppedWholeSoNoLaterBranchInheritsItsTurn() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do:
                    - choose:
                        - if: { nope: 1 }
                          then: [ { a: first } ]
                        - if: { pass: x }
                          then: [ { b: second } ]
                    - c: after
                """);

        assertThat(parsed.rules().get(0).steps()).extracting(Step::kind).containsExactly("action");
        assertThat(codes(parsed)).containsExactly("MTR-0057");
    }

    @Test
    void anIfWithABadConditionIsSkippedButItsBranchesAreStillChecked() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do:
                    - if: "1 +"
                      then: [ { missing: 1 } ]
                    - a: after
                """);

        assertThat(parsed.rules().get(0).steps()).hasSize(1);
        assertThat(codes(parsed)).containsExactly("MTR-0022", "MTR-0057");
        assertThat(targets(parsed)).containsExactly("behavior.0.do.0.if", "behavior.0.do.0.then.0");
    }

    @Test
    void aRuleLevelConditionThatCannotCompileSkipsTheRule() {
        ParsedRules parsed = f.compile("""
                - on: click
                  if: [ "player.level > 1", { nope: 1 } ]
                  do: [ { a: x } ]
                """);

        assertThat(parsed.rules()).isEmpty();
        assertThat(codes(parsed)).containsExactly("MTR-0057");
        assertThat(targets(parsed)).containsExactly("behavior.0.if.1");
    }

    @Test
    void aRuleWithNoLoadableStepIsSkippedAndSaysSo() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do: [ { nope: 1 } ]
                """);

        assertThat(parsed.rules()).isEmpty();
        assertThat(codes(parsed)).containsExactly("MTR-0057", "MTR-0021");
        assertThat(parsed.problems().get(1).severity()).isEqualTo(Severity.WARN);
    }

    @Test
    void nonListSectionsAndMissingFlowsAreNotProblems() {
        assertThat(f.compile("oops").problems()).isEmpty();
        assertThat(FlowCompiler.compile(FlowFixture.OWNER, null, null, f.logic.lookup()).isEmpty()).isTrue();
        List<Diagnostic> none = f.compile("- on: click\n  do: [ { a: x } ]").problems();
        assertThat(none).isEmpty();
    }
}

