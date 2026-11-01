package dev.materione.behavior.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.Cancellation;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.behavior.runtime.BehaviorEvent;
import dev.materione.behavior.flow.FlowFixture;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The interpreter (logic.md §5.2) against a hand-driven scheduler: every step kind, suspension,
 * thread hops, the run budget, cancellation and the trace. Rules are written as the YAML an author
 * writes and compiled by the real compiler, so these tests also pin what the YAML means.
 */
class FlowInterpreterTest {

    private static final Object ANCHOR = "player-1";

    private final FlowFixture f = new FlowFixture();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final List<TraceEvent> trace = new ArrayList<>();
    private FlowInterpreter interpreter = interpreter(RunBudget.DEFAULT);

    private FlowInterpreter interpreter(RunBudget budget) {
        var made = new FlowInterpreter(scheduler, budget, diagnostics::add);
        made.traceTo(trace::add);
        return made;
    }

    private Rule rule(String steps) {
        return rule(steps, null);
    }

    private Rule rule(String steps, String flows) {
        String body = steps.lines().map(line -> "    " + line).reduce("", (a, b) -> a + b + "\n");
        ParsedRules parsed = flows == null
                ? f.compile("- on: click\n  do:\n" + body)
                : f.compile("- on: click\n  do:\n" + body, flows);
        assertThat(parsed.problems()).allSatisfy(problem -> assertThat(problem.code().code())
                .as("only the deliberate args-outside-a-flow warning is tolerated").isEqualTo("MTR-0060"));
        if (!tolerateRootWarnings) {
            assertThat(parsed.problems()).isEmpty();
        }
        return parsed.rules().get(0);
    }

    private boolean tolerateRootWarnings;

    private MapLogicContext context() {
        return new MapLogicContext(Map.of("player", Map.of("level", 12, "uuid", "u1"), "vars", new java.util.LinkedHashMap<String, Object>()));
    }

    private FlowRun run(Rule rule) {
        return interpreter.start(rule, context(), RunOwner.of("u1", ANCHOR));
    }

    private List<String> paths(TraceEvent.Type type) {
        return trace.stream().filter(event -> event.type() == type).map(event -> event.path().toString()).toList();
    }

    // --- sequencing and branches -----------------------------------------------------

    @Test
    void stepsRunInOrderAndAnEmptyRunFinishes() {
        FlowRun run = run(rule("- a: 1\n- b: 2\n- c: 3"));

        assertThat(f.ran).containsExactly("a:1", "b:2", "c:3");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
        assertThat(run.isFinished()).isTrue();
        assertThat(run.stepsExecuted()).isEqualTo(3);
        assertThat(interpreter.liveRuns()).isZero(); // a run that never parked is never recorded
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void ifPicksTheBranchAndContinuesAfterIt() {
        run(rule("""
                - if: "player.level > 5"
                  then: [ { a: first }, { b: second } ]
                  else: [ { c: other } ]
                - pure: after
                - if: "player.level > 50"
                  then: [ { a: never } ]
                  else: [ { c: other } ]
                """));

        assertThat(f.ran).containsExactly("a:first", "b:second", "pure:after", "c:other");
        assertThat(trace).filteredOn(event -> event.type() == TraceEvent.Type.BRANCH)
                .extracting(TraceEvent::detail).containsExactly("then", "else");
    }

    @Test
    void anUnavailableConditionCancelsAndDoesNotRunItsElseBranch() {
        var context = unavailableContext();
        var event = new BehaviorEvent("click", "", context, null, ANCHOR);
        FlowRun run = interpreter.start(rule("""
                - if: "vars.player.shop.coins == 0"
                  then: [ { a: grant } ]
                  else: [ { b: fallback } ]
                - c: after
                """), event.context(), RunOwner.of("u1", ANCHOR));

        assertThat(event.isCancelRequested()).isTrue();
        assertThat(run.outcome()).isEqualTo(RunOutcome.ABORTED);
        assertThat(f.ran).isEmpty();
        assertThat(diagnostics).singleElement().satisfies(d -> assertThat(d.message()).contains("not owned"));
    }

    @Test
    void anUnavailableActionTemplateCancelsAndDoesNotContinue() {
        var context = unavailableContext();
        var event = new BehaviorEvent("click", "", context, null, ANCHOR);
        FlowRun run = interpreter.start(rule("""
                - say: "${vars.player.shop.coins}"
                - a: after
                """), event.context(), RunOwner.of("u1", ANCHOR));

        assertThat(event.isCancelRequested()).isTrue();
        assertThat(run.outcome()).isEqualTo(RunOutcome.ABORTED);
        assertThat(f.ran).isEmpty();
    }

    private static MapLogicContext unavailableContext() {
        var id = NamespacedId.of("shop", "coins");
        var declaration = new VariableDeclaration(id, VariableScope.PLAYER, VariableType.NUMBER, 0, "");
        return new MapLogicContext(Map.of()).withVariables(new VariableBinding() {
            @Override public Optional<VariableDeclaration> declaration(NamespacedId asked) {
                return asked.equals(id) ? Optional.of(declaration) : Optional.empty();
            }
            @Override public Optional<Object> stored(VariableDeclaration ignored) {
                throw new ReadUnavailableException("not owned");
            }
            @Override public boolean store(VariableDeclaration ignored, Object value) { return false; }
        });
    }

    @Test
    void ifNestedInsideBranchesRunsDepthFirst() {
        run(rule("""
                - if: "true"
                  then:
                    - a: 1
                    - if: "false"
                      then: [ { b: no } ]
                      else:
                        - c: 2
                        - if: "true"
                          then: [ { pure: 3 } ]
                    - a: 4
                - b: 5
                """));

        assertThat(f.ran).containsExactly("a:1", "c:2", "pure:3", "a:4", "b:5");
    }

    @Test
    void chooseRunsTheFirstMatchingBranchOnlyAndNeverAsksTheRest() {
        run(rule("""
                - choose:
                    - if: { deny: first }
                      then: [ { a: first } ]
                    - if: { pass: second }
                      then: [ { b: second } ]
                    - if: { pass: third }
                      then: [ { c: third } ]
                  else:
                    - pure: fallback
                - pure: after
                """));

        assertThat(f.ran).containsExactly("b:second", "pure:after");
        assertThat(f.asked).containsExactly("deny:first", "pass:second");
        assertThat(trace).filteredOn(event -> event.type() == TraceEvent.Type.BRANCH)
                .extracting(TraceEvent::detail).containsExactly("case 1");
    }

    @Test
    void chooseFallsToElseWhenNothingMatches() {
        run(rule("""
                - choose:
                    - if: { deny: 1 }
                      then: [ { a: no } ]
                  else:
                    - pure: fallback
                """));

        assertThat(f.ran).containsExactly("pure:fallback");
    }

    @Test
    void aConditionThatThrowsCountsAsFalseAndIsReported() {
        run(rule("""
                - if: { boomy: boom }
                  then: [ { a: never } ]
                  else: [ { b: else } ]
                """));

        assertThat(f.ran).containsExactly("b:else");
        assertThat(diagnostics).singleElement().satisfies(problem -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0023");
            assertThat(problem.message()).contains("condition failed: predicate exploded");
            assertThat(problem.target().field()).containsExactly("behavior", "0", "do", "0");
        });
    }

    @Test
    void chanceZeroSkipsTheStepAndTracesIt() {
        run(rule("- a: never\n  chance: 0\n- b: always"));

        assertThat(f.ran).containsExactly("b:always");
        assertThat(paths(TraceEvent.Type.STEP_SKIPPED)).containsExactly("behavior.0.do.0");
    }

    // --- failure and require ---------------------------------------------------------

    @Test
    void anUnhandledFailureIsReportedAndTheChainContinues() {
        FlowRun run = run(rule("- fails: x\n- a: after"));

        assertThat(f.ran).containsExactly("a:after");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
        assertThat(diagnostics).singleElement().satisfies(problem -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0023");
            assertThat(problem.message()).contains("action 'fails' failed: no luck").contains("behavior.0.do.0");
        });
    }

    @Test
    void requireStopsTheFlowAtTheFirstFailureRunsElseAndSkipsEverythingAfter() {
        FlowRun run = run(rule("""
                - require:
                    - a: pay
                    - fails: take
                    - b: never-given
                  else:
                    - c: refused
                - pure: never-after
                """));

        assertThat(f.ran).containsExactly("a:pay", "c:refused");
        assertThat(run.outcome()).isEqualTo(RunOutcome.REQUIRE_FAILED);
        assertThat(paths(TraceEvent.Type.REQUIRE_FAILED)).containsExactly("behavior.0.do.0");
        assertThat(diagnostics).isEmpty(); // a handled failure is control flow, not a fault
        assertThat(trace).filteredOn(event -> event.type() == TraceEvent.Type.STEP_FAILED)
                .extracting(TraceEvent::detail).singleElement().asString().contains("no luck");
    }

    @Test
    void requireThatSucceedsLetsTheFlowContinue() {
        FlowRun run = run(rule("""
                - require: [ { a: pay }, { b: give } ]
                  else: [ { c: refused } ]
                - pure: after
                """));

        assertThat(f.ran).containsExactly("a:pay", "b:give", "pure:after");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
    }

    @Test
    void requireWithoutElseJustEndsTheRun() {
        FlowRun run = run(rule("- require: [ { fails: x } ]\n- pure: after"));

        assertThat(f.ran).isEmpty();
        assertThat(run.outcome()).isEqualTo(RunOutcome.REQUIRE_FAILED);
    }

    @Test
    void aFailureNestedInABranchInsideARequireFailsTheRequire() {
        run(rule("""
                - require:
                    - if: "true"
                      then: [ { fails: deep } ]
                    - b: never
                  else: [ { c: refused } ]
                """));

        assertThat(f.ran).containsExactly("c:refused");
    }

    @Test
    void aFailureInsideACalledFlowFailsTheCallersRequireAndStopsTheWholeRun() {
        FlowRun run = run(rule("""
                - require:
                    - call: charge
                  else: [ { c: refused } ]
                - pure: never
                """, "charge: [ { a: ok }, { fails: nsf }, { b: never } ]"));

        assertThat(f.ran).containsExactly("a:ok", "c:refused");
        assertThat(run.outcome()).isEqualTo(RunOutcome.REQUIRE_FAILED);
    }

    @Test
    void aFailureOutsideAnyRequireDoesNotTriggerAnEnclosingFlowsGuardAfterTheRequireEnded() {
        run(rule("""
                - require: [ { a: ok } ]
                - fails: later
                - b: continues
                """));

        assertThat(f.ran).containsExactly("a:ok", "b:continues");
    }

    @Test
    void aThrowingActionInsideARequireFailsItAndIsStillReported() {
        run(rule("""
                - require: [ { boom: x } ]
                  else: [ { c: refused } ]
                """));

        assertThat(f.ran).containsExactly("c:refused");
        assertThat(diagnostics).singleElement().satisfies(problem ->
                assertThat(problem.message()).contains("action exploded"));
    }

    @Test
    void aFailingStepInsideAnElseDoesNotTriggerAnOuterRequire() {
        run(rule("""
                - require:
                    - require: [ { fails: inner } ]
                      else: [ { fails: noisy }, { b: second-else-step } ]
                    - a: never
                  else: [ { c: outer-else } ]
                """));

        assertThat(f.ran).containsExactly("b:second-else-step"); // the run ends after the inner else
    }

    @Test
    void aBlockedRequireFailsClosedAndRunsItsElse() {
        ParsedRules parsed = f.compile("""
                - on: click
                  do:
                    - require: [ { nope: 1 }, { a: give } ]
                      else: [ { c: refused } ]
                    - pure: after
                """);
        FlowRun run = interpreter.start(parsed.rules().get(0), context(), RunOwner.of("u1", ANCHOR));

        assertThat(f.ran).containsExactly("c:refused");
        assertThat(run.outcome()).isEqualTo(RunOutcome.REQUIRE_FAILED);
    }

    // --- stop and cancel -------------------------------------------------------------

    @Test
    void stopEndsTheRunEvenInsideACalledFlow() {
        FlowRun run = run(rule("- a: 1\n- call: halt\n- b: never", "halt: [ { c: 2 }, { stop: true }, { pure: never } ]"));

        assertThat(f.ran).containsExactly("a:1", "c:2");
        assertThat(run.outcome()).isEqualTo(RunOutcome.STOPPED);
    }

    @Test
    void stopWithCancelRequestsCancellationThroughTheFacet() {
        var event = new dev.materione.behavior.runtime.BehaviorEvent("click", "", context(), null, ANCHOR);
        Rule rule = rule("- stop: { cancel: true }");

        interpreter.start(rule, event.context(), RunOwner.of("u1", ANCHOR));

        assertThat(event.isCancelRequested()).isTrue();
        assertThat(event.context().require(Cancellation.FACET).requested()).isTrue();
    }

    @Test
    void plainStopDoesNotCancelTheEvent() {
        var event = new dev.materione.behavior.runtime.BehaviorEvent("click", "", context(), null, ANCHOR);
        interpreter.start(rule("- stop"), event.context(), RunOwner.of("u1", ANCHOR));

        assertThat(event.isCancelRequested()).isFalse();
    }

    // --- suspension ------------------------------------------------------------------

    @Test
    void waitParksTheRunWithoutBlockingAndResumesOnTheScheduler() {
        FlowRun run = run(rule("- a: before\n- wait: 40\n- b: after"));

        assertThat(f.ran).containsExactly("a:before");
        assertThat(run.isParked()).isTrue();
        assertThat(run.outcome()).isNull();
        assertThat(interpreter.parkedRuns("u1")).isEqualTo(1);
        assertThat(interpreter.liveRuns()).isEqualTo(1);

        scheduler.advance(39);
        assertThat(f.ran).containsExactly("a:before");
        scheduler.advance(1);

        assertThat(f.ran).containsExactly("a:before", "b:after");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
        assertThat(interpreter.parkedRuns("u1")).isZero();
        assertThat(interpreter.liveRuns()).isZero();
        assertThat(scheduler.history).singleElement().satisfies(hop -> {
            assertThat(hop.delay()).isEqualTo(40);
            assertThat(hop.thread()).isEqualTo(Threading.PLAYER_REGION);
            assertThat(hop.anchor()).isEqualTo(ANCHOR);
        });
        assertThat(paths(TraceEvent.Type.SUSPENDED)).containsExactly("behavior.0.do.1");
    }

    @Test
    void waitZeroYieldsUntilTheNextTick() {
        run(rule("- wait: 0\n- a: later"));

        assertThat(f.ran).isEmpty();
        scheduler.runPending();
        assertThat(f.ran).containsExactly("a:later");
    }

    @Test
    void anActionsDelayReallyDelaysItAndTheFlowWaitsWithIt() {
        run(rule("- a: first\n- b: late\n  delay: 20\n- c: last"));

        assertThat(f.ran).containsExactly("a:first");
        scheduler.advance(19);
        assertThat(f.ran).containsExactly("a:first");
        scheduler.advance(1);
        assertThat(f.ran).containsExactly("a:first", "b:late", "c:last");
    }

    @Test
    void aRunKeepsItsStateAcrossAWaitInsideNestedBranchesAndCalls() {
        FlowRun run = run(rule("""
                - if: "true"
                  then:
                    - call: slow
                    - b: back-in-branch
                - c: end
                """, "slow: [ { a: start }, { wait: 5 }, { pure: end-of-flow } ]"));

        assertThat(f.ran).containsExactly("a:start");
        scheduler.advance(5);
        assertThat(f.ran).containsExactly("a:start", "pure:end-of-flow", "b:back-in-branch", "c:end");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
    }

    @Test
    void aWaitDoesNotReRollAChanceOrCountAStepTwice() {
        FlowRun run = run(rule("- a: x\n  chance: 1\n  delay: 3\n- wait: 3\n- b: y"));
        scheduler.advance(10);

        assertThat(f.ran).containsExactly("a:x", "b:y");
        assertThat(run.stepsExecuted()).isEqualTo(3);
    }

    // --- threads ---------------------------------------------------------------------

    @Test
    void aStepForAnotherThreadHopsThereAndBackKeepingTheWrittenOrder() {
        FlowRun run = run(rule("- a: player\n- g: console\n- b: player-again\n- pure: any"));

        assertThat(f.ran).containsExactly("a:player"); // g needs the global thread: the run parked
        scheduler.runPending();

        assertThat(f.ran).containsExactly("a:player", "g:console", "b:player-again", "pure:any");
        assertThat(scheduler.history).extracting(ManualScheduler.Scheduled::thread)
                .containsExactly(Threading.GLOBAL, Threading.PLAYER_REGION);
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
        assertThat(interpreter.parkedRuns("u1")).isZero(); // a hop is not a wait
    }

    @Test
    void consecutiveStepsForTheSameThreadShareOneHop() {
        run(rule("- g: 1\n- g: 2\n- g: 3"));
        scheduler.runPending();

        assertThat(f.ran).containsExactly("g:1", "g:2", "g:3");
        assertThat(scheduler.history).hasSize(1);
    }

    @Test
    void whenTheCurrentThreadAlreadySuitsNothingIsScheduled() {
        scheduler.current = Threading.GLOBAL;
        run(rule("- g: 1\n- pure: 2"));

        assertThat(f.ran).containsExactly("g:1", "pure:2");
        assertThat(scheduler.history).isEmpty();
    }

    @Test
    void aPredicateReadsLiveStateSoItHopsBackToThePlayersThreadFirst() {
        run(rule("""
                - g: console
                - if: { pass: x }
                  then: [ { pure: fine } ]
                """));
        scheduler.runPending();

        assertThat(f.ran).containsExactly("g:console", "pure:fine");
        assertThat(scheduler.history).extracting(ManualScheduler.Scheduled::thread)
                .containsExactly(Threading.GLOBAL, Threading.PLAYER_REGION);
    }

    @Test
    void aStopCancelAfterAHopIsTooLateForTheEventButHarmless() {
        var event = new dev.materione.behavior.runtime.BehaviorEvent("click", "", context(), null, ANCHOR);
        interpreter.start(rule("- g: console\n- stop: { cancel: true }"), event.context(), RunOwner.of("u1", ANCHOR));

        assertThat(event.isCancelRequested()).isFalse(); // the handler has returned by the time it is asked
        scheduler.runPending();
        assertThat(event.isCancelRequested()).isTrue(); // the request is recorded; the bridge ignores it by then
    }

    // --- calls and arguments ---------------------------------------------------------

    @Test
    void callPassesArgumentsThatOnlyTheCalleeSeesAndNestedCallsGetTheirOwn() {
        tolerateRootWarnings = true; // the rule body reads args on purpose, to show it is empty there
        run(rule("""
                - say: "outer ${args.n}"
                - call: { flow: outer, with: { n: "${player.level}", who: "Alex" } }
                - say: "outer again ${args.n}"
                """, """
                outer:
                  - say: "outer-flow ${args.n} ${args.who}"
                  - call: { flow: inner, with: { n: 99 } }
                  - say: "outer-flow after ${args.n}"
                inner:
                  - say: "inner ${args.n} [${args.who}]"
                """));

        assertThat(f.ran).containsExactly(
                "say:outer ",                       // no call frame: args is empty
                "say:outer-flow 12 Alex",           // typed expression kept its number, the literal its text
                "say:inner 99 []",                  // the callee gets exactly what it was given, no inheritance
                "say:outer-flow after 12",          // back in the caller's frame the args are the caller's again
                "say:outer again ");
    }

    @Test
    void argumentsKeepTheirTypeSoTheCalleeCanDoArithmeticOnThem() {
        run(rule("""
                - call: { flow: price, with: { base: "${player.level * 10}" } }
                """, """
                price:
                  - if: "args.base == 120"
                    then: [ { a: ok } ]
                    else: [ { b: wrong } ]
                """));

        assertThat(f.ran).containsExactly("a:ok");
    }

    @Test
    void theRunLocalVariablesAreTheHostsAndEveryFrameSeesThem() {
        MapLogicContext ctx = context();
        ctx.locals().put("flag", "on");

        interpreter.start(rule("- call: check", "check: [ { if: \"vars.flag == 'on'\", then: [ { a: seen } ] } ]"),
                ctx, RunOwner.of("u1", ANCHOR));

        assertThat(f.ran).containsExactly("a:seen");
    }

    // --- budget ----------------------------------------------------------------------

    @Test
    void aRunPastItsStepBudgetIsAbortedWithADiagnosticAndNothingElseBreaks() {
        String steps = "- pure: x\n".repeat(1001);
        FlowRun run = run(rule(steps));

        assertThat(run.outcome()).isEqualTo(RunOutcome.ABORTED);
        assertThat(f.ran).hasSize(1000);
        assertThat(diagnostics).singleElement().satisfies(problem -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0059");
            assertThat(problem.message()).contains("more than 1000 steps");
            assertThat(problem.target().field()).containsExactly("behavior", "0", "do", "1000");
        });
    }

    @Test
    void theStepBudgetCountsBranchesAndCallsToo() {
        interpreter = interpreter(new RunBudget(5, 64, 16));
        FlowRun run = run(rule("""
                - if: "true"
                  then: [ { pure: 1 }, { pure: 2 } ]
                - call: tail
                """, "tail: [ { pure: 3 }, { pure: 4 } ]"));

        // if(1) pure(2) pure(3) call(4) pure(5) -> the sixth step is over budget
        assertThat(run.outcome()).isEqualTo(RunOutcome.ABORTED);
        assertThat(f.ran).containsExactly("pure:1", "pure:2", "pure:3");
    }

    @Test
    void callDepthIsGuardedAtRunTimeToo() {
        interpreter = interpreter(new RunBudget(1000, 64, 2));
        FlowRun run = run(rule("- call: one", "one: [ { call: two } ]\ntwo: [ { call: three } ]\nthree: [ { pure: x } ]"));

        assertThat(run.outcome()).isEqualTo(RunOutcome.ABORTED);
        assertThat(f.ran).isEmpty();
        assertThat(diagnostics.get(0).message()).contains("calls nested deeper than 2");
    }

    @Test
    void aPlayerAtTheirParkedRunLimitHasNewRunsRefused() {
        interpreter = interpreter(new RunBudget(1000, 2, 16));
        Rule slow = rule("- wait: 100\n- a: done");

        FlowRun first = run(slow);
        FlowRun second = run(slow);
        FlowRun third = run(slow);

        assertThat(first.isParked()).isTrue();
        assertThat(second.isParked()).isTrue();
        assertThat(third.outcome()).isEqualTo(RunOutcome.ABORTED);
        assertThat(interpreter.parkedRuns("u1")).isEqualTo(2);
        assertThat(diagnostics).singleElement().satisfies(problem ->
                assertThat(problem.code().code()).isEqualTo("MTR-0059"));

        // another player is unaffected, and room opens as runs finish
        FlowRun other = interpreter.start(slow, context(), RunOwner.of("u2", "player-2"));
        assertThat(other.isParked()).isTrue();
        scheduler.advance(100);
        assertThat(interpreter.parkedRuns("u1")).isZero();
        assertThat(f.ran).containsExactly("a:done", "a:done", "a:done");
        assertThat(run(slow).isParked()).isTrue();
    }

    @Test
    void aSchedulerThatCannotScheduleAbortsTheRunInsteadOfLosingIt() {
        var broken = new RunScheduler() {
            @Override
            public boolean canRunInline(Object anchor, Threading thread) {
                return false;
            }

            @Override
            public Handle schedule(Object anchor, Threading thread, long delayTicks, Runnable task, Runnable dropped) {
                throw new UnsupportedOperationException("no scheduler");
            }
        };
        var made = new FlowInterpreter(broken, RunBudget.DEFAULT, diagnostics::add);

        FlowRun run = made.start(rule("- g: x"), context(), RunOwner.of("u1", ANCHOR));

        assertThat(run.outcome()).isEqualTo(RunOutcome.ABORTED);
        assertThat(diagnostics.get(0).message()).contains("could not schedule the run");
    }

    // --- cancellation ----------------------------------------------------------------

    @Test
    void cancellingByKeyDropsTheParkedRunsAndTheirPendingResumes() {
        Rule slow = rule("- wait: 30\n- a: never");
        FlowRun mine = interpreter.start(slow, context(), new RunOwner("u1", ANCHOR, "session-1"));
        FlowRun alsoMine = interpreter.start(slow, context(), new RunOwner("u1", ANCHOR, "session-1"));
        FlowRun other = interpreter.start(slow, context(), new RunOwner("u1", ANCHOR, "session-2"));
        FlowRun keyless = run(slow);

        assertThat(interpreter.cancel("session-1")).isEqualTo(2);

        assertThat(mine.outcome()).isEqualTo(RunOutcome.CANCELLED);
        assertThat(alsoMine.outcome()).isEqualTo(RunOutcome.CANCELLED);
        assertThat(other.isParked()).isTrue();
        assertThat(keyless.isParked()).isTrue();
        assertThat(interpreter.parkedRuns("u1")).isEqualTo(2);
        scheduler.advance(30);
        assertThat(f.ran).containsExactly("a:never", "a:never"); // only the two that were not cancelled
        assertThat(interpreter.cancel("session-1")).isZero();
    }

    @Test
    void aRunWithoutAKeyIsNotReachableByKeyAndNullMatchesNothing() {
        run(rule("- wait: 30\n- a: kept"));

        assertThat(interpreter.cancel(null)).isZero();
        assertThat(interpreter.cancel("anything")).isZero();
        scheduler.advance(30);
        assertThat(f.ran).containsExactly("a:kept");
    }

    @Test
    void aRunWithAKeyIsReachableEvenWhileItIsRunning() {
        // the first step cancels its own session through stop: cancel_session_runs
        FlowRun self = interpreter.start(rule("- a: before\n- stop: { cancel_session_runs: true }\n- b: never"),
                context(), new RunOwner("u1", ANCHOR, "session-1"));
        FlowRun sibling = interpreter.start(rule("- wait: 10\n- c: never"), context(),
                new RunOwner("u1", ANCHOR, "session-1"));

        assertThat(self.outcome()).isEqualTo(RunOutcome.STOPPED);
        assertThat(sibling.isParked()).isTrue();

        interpreter.start(rule("- stop: { cancel_session_runs: true }"), context(),
                new RunOwner("u1", ANCHOR, "session-1"));
        assertThat(sibling.outcome()).isEqualTo(RunOutcome.CANCELLED);
        scheduler.advance(10);
        assertThat(f.ran).containsExactly("a:before");
    }

    @Test
    void cancellingAPlayerEndsTheirParkedRuns() {
        FlowRun run = run(rule("- wait: 30\n- a: never"));

        assertThat(interpreter.cancelPlayer("u1")).isEqualTo(1);

        assertThat(run.outcome()).isEqualTo(RunOutcome.CANCELLED);
        assertThat(interpreter.parkedRuns("u1")).isZero();
        scheduler.advance(30);
        assertThat(f.ran).isEmpty();
    }

    @Test
    void aRunWhosePlayerLeavesIsDroppedAndItsCountReleased() {
        FlowRun run = run(rule("- wait: 30\n- a: never"));

        scheduler.dropAll();

        assertThat(run.outcome()).isEqualTo(RunOutcome.DROPPED);
        assertThat(interpreter.parkedRuns("u1")).isZero();
        assertThat(interpreter.liveRuns()).isZero();
    }

    // --- trace -----------------------------------------------------------------------

    @Test
    void theTraceNamesEveryStepBoundaryByPathInOrder() {
        run(rule("""
                - a: 1
                - if: "true"
                  then: [ { fails: 2 } ]
                - call: tail
                """, "tail: [ { b: 3 } ]"));

        assertThat(trace).extracting(event -> event.type() + "@" + event.path()).containsExactly(
                "RUN_STARTED@behavior.0",
                "STEP_OK@behavior.0.do.0",
                "BRANCH@behavior.0.do.1",
                "STEP_FAILED@behavior.0.do.1.then.0",
                "CALLED@behavior.0.do.2",
                "STEP_OK@flows.tail.0",
                "RUN_ENDED@behavior.0");
        assertThat(trace).extracting(TraceEvent::runId).containsOnly(trace.get(0).runId());
        assertThat(trace.get(trace.size() - 1).detail()).startsWith("FINISHED");
    }

    @Test
    void noSinkMeansNoTraceAndABrokenSinkNeverBreaksARun() {
        interpreter.traceTo(null);
        run(rule("- a: 1"));
        assertThat(trace).isEmpty();

        interpreter.traceTo(event -> {
            throw new IllegalStateException("sink exploded");
        });
        FlowRun run = run(rule("- a: 2"));
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
        assertThat(f.ran).containsExactly("a:1", "a:2");
    }

    @Test
    void runIdsAreUniquePerRun() {
        FlowRun one = run(rule("- a: 1"));
        FlowRun two = run(rule("- a: 2"));
        assertThat(one.id()).isNotEqualTo(two.id());
        assertThat(one.rule().id()).isNotBlank();
        assertThat(one.owner().playerKey()).isEqualTo("u1");
    }
}
