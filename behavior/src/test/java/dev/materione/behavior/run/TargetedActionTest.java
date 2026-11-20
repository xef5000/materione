package dev.materione.behavior.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ResolvedTarget;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.logic.TargetResolution;
import dev.materione.api.logic.TargetResolver;
import dev.materione.api.logic.TargetSpec;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.behavior.flow.ActionStep;
import dev.materione.behavior.flow.FlowFixture;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Target addressing in the interpreter (logic.md §39): a step with {@code target:} is resolved, then the action runs
 * once per subject on the subject's own region, in order, and the run continues on its own. A scheduler that
 * models regions (a thread owns exactly one anchor) stands in for Folia; the resolver is scripted.
 */
class TargetedActionTest {

    private static final Object RUN = "run-player";

    /** A scheduler where a thread owns one anchor: the dispatch seam the engine must respect. */
    private static final class Regions implements RunScheduler {
        record Task(Object anchor, Threading thread, Runnable task, Runnable dropped) {}

        Object here = RUN;
        final Deque<Task> queue = new ArrayDeque<>();
        final List<String> hops = new ArrayList<>();

        @Override
        public boolean canRunInline(Object anchor, Threading thread) {
            return thread == Threading.ANY || thread == Threading.PLAYER_REGION && Objects.equals(here, anchor);
        }

        @Override
        public Handle schedule(Object anchor, Threading thread, long delayTicks, Runnable task, Runnable dropped) {
            hops.add(thread + "@" + anchor);
            var entry = new Task(anchor, thread, task, dropped);
            queue.add(entry);
            return () -> queue.remove(entry);
        }

        void drain() {
            while (!queue.isEmpty()) {
                Task next = queue.poll();
                Object before = here;
                here = next.anchor();
                try {
                    next.task().run();
                } finally {
                    here = before;
                }
            }
        }

        void retireAll() {
            List<Task> all = new ArrayList<>(queue);
            queue.clear();
            all.forEach(task -> task.dropped().run());
        }
    }

    private record Subject(String name, Object anchor, TargetKind kind) implements ResolvedTarget {
        @Override
        public String describe() {
            return kind.id() + " " + name;
        }
    }

    /** {@code poke: {value, target}}: records who it acted on and in which region. */
    private static final class Poke implements ActionType<String> {
        private final List<String> ran;
        private final Regions regions;
        private final Function<String, ActionOutcome> outcome;

        Poke(List<String> ran, Regions regions, Function<String, ActionOutcome> outcome) {
            this.ran = ran;
            this.regions = regions;
            this.outcome = outcome;
        }

        @Override
        public String id() {
            return "poke";
        }

        @Override
        public ActionDescription describe() {
            return new ActionDescription(new SelfDescription("Poke", "Pokes the subject",
                    List.of(ParameterSchema.of("value", ParameterType.STRING, true)), "1.0.0"),
                    LogicCategory.PLAYER, "value").withTargets(TargetKind.PLAYER, TargetKind.ENTITY);
        }

        @Override
        public Threading threading() {
            return Threading.PLAYER_REGION;
        }

        @Override
        public String parse(ParamReader params) {
            return params.text("value");
        }

        @Override
        public ActionOutcome execute(String params, LogicContext context) {
            String who = context.facet(ResolvedTarget.FACET).map(ResolvedTarget::describe).orElse("self");
            ran.add("poke:" + params + " on " + who + " @" + regions.here);
            return outcome.apply(who);
        }
    }

    private final FlowFixture f = new FlowFixture();
    private final Regions regions = new Regions();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final List<TraceEvent> trace = new ArrayList<>();
    private final List<TargetSpec> asked = new ArrayList<>();
    private final List<Set<TargetKind>> admitted = new ArrayList<>();
    private Function<String, ActionOutcome> outcome = who -> ActionOutcome.OK;
    private Function<TargetSpec, TargetResolution> resolution = spec -> TargetResolution.none("nobody");

    private FlowInterpreter interpreter(boolean withResolver) {
        f.logic.action(new Poke(f.ran, regions, who -> outcome.apply(who)));
        var interpreter = new FlowInterpreter(regions, RunBudget.DEFAULT, diagnostics::add);
        interpreter.traceTo(trace::add);
        if (withResolver) {
            TargetResolver resolver = (spec, admits, context, reads) -> {
                asked.add(spec);
                admitted.add(admits);
                return CompletableFuture.completedFuture(resolution.apply(spec));
            };
            interpreter.targetsFrom(resolver);
        }
        return interpreter;
    }

    private Rule rule(String steps) {
        String body = steps.lines().map(line -> "    " + line).reduce("", (a, b) -> a + b + "\n");
        ParsedRules parsed = f.compile("- on: click\n  do:\n" + body);
        assertThat(parsed.problems()).isEmpty();
        return parsed.rules().get(0);
    }

    private FlowRun run(FlowInterpreter interpreter, Rule rule) {
        var context = new MapLogicContext(Map.of("player", Map.of("level", 12), "vars", new java.util.LinkedHashMap<String, Object>()));
        FlowRun run = interpreter.start(rule, context, RunOwner.of("u1", RUN));
        regions.drain();
        return run;
    }

    private static Subject player(String name) {
        return new Subject(name, "region-" + name, TargetKind.PLAYER);
    }

    private static Subject mob(String name) {
        return new Subject(name, "region-" + name, TargetKind.ENTITY);
    }

    // --- the step, without a target ------------------------------------------------------

    @Test
    void aStepWithNoTargetActsOnTheRunsOwnPlayerAndNeverAsksTheResolver() {
        var interpreter = interpreter(true);
        FlowRun run = run(interpreter, rule("- poke: hi"));

        assertThat(f.ran).containsExactly("poke:hi on self @run-player");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
        assertThat(asked).isEmpty();
        assertThat(trace).filteredOn(event -> event.type() == TraceEvent.Type.STEP_OK)
                .extracting(TraceEvent::detail).containsExactly("");
        var step = (ActionStep<?>) run.rule().steps().get(0);
        assertThat(step.target()).isNull();
    }

    @Test
    void selfIsNoTargetEither() {
        var interpreter = interpreter(true);
        FlowRun run = run(interpreter, rule("- poke: {value: hi, target: self}"));

        assertThat(((ActionStep<?>) run.rule().steps().get(0)).target()).isNull();
        assertThat(asked).isEmpty();
        assertThat(f.ran).containsExactly("poke:hi on self @run-player");
    }

    // --- resolution and dispatch ---------------------------------------------------------

    @Test
    void aTargetIsResolvedAndTheActionRunsOnTheSubjectsRegion() {
        resolution = spec -> TargetResolution.of(player("Bob"));
        var interpreter = interpreter(true);
        FlowRun run = run(interpreter, rule("- poke: {value: hi, target: {arg: to}}\n- pure: after"));

        assertThat(asked).containsExactly(new TargetSpec.Arg("to"));
        assertThat(admitted).containsExactly(Set.of(TargetKind.PLAYER, TargetKind.ENTITY));
        // the effect ran where Bob is, not where the run's player is, and the flow went on afterwards
        assertThat(f.ran).containsExactly("poke:hi on player Bob @region-Bob", "pure:after");
        assertThat(regions.hops).contains("PLAYER_REGION@region-Bob");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
        assertThat(trace).filteredOn(event -> event.type() == TraceEvent.Type.STEP_OK)
                .extracting(TraceEvent::detail).containsExactly("on player Bob", "");
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void aSubjectOwnedByTheCurrentThreadIsActedOnInline() {
        resolution = spec -> TargetResolution.of(new Subject("Self2", RUN, TargetKind.PLAYER));
        var interpreter = interpreter(true);
        run(interpreter, rule("- poke: {value: hi, target: {player: Alex}}"));

        assertThat(f.ran).containsExactly("poke:hi on player Self2 @run-player");
        assertThat(regions.hops).isEmpty();
    }

    @Test
    void aSetIsActedOnOneAfterAnotherInResolvedOrder() {
        resolution = spec -> TargetResolution.of(List.of(mob("a"), player("b"), mob("c")), "none");
        var interpreter = interpreter(true);
        FlowRun run = run(interpreter, rule("- poke: {value: hit, target: {nearby: {radius: 8, limit: 3}}}"));

        assertThat(f.ran).containsExactly(
                "poke:hit on entity a @region-a", "poke:hit on player b @region-b", "poke:hit on entity c @region-c");
        assertThat(asked).containsExactly(new TargetSpec.Nearby(8, null, 3));
        assertThat(trace).filteredOn(event -> event.type() == TraceEvent.Type.STEP_OK)
                .extracting(TraceEvent::detail).containsExactly("on entity a, player b, entity c");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
    }

    // --- failure -------------------------------------------------------------------------

    @Test
    void aTargetThatDoesNotResolveFailsTheActionAndDoesNotFallBackToTheRunsPlayer() {
        resolution = spec -> TargetResolution.none("no player named 'Ghost' is online");
        var interpreter = interpreter(true);
        FlowRun run = run(interpreter, rule("- poke: {value: hi, target: {player: Ghost}}\n- pure: after"));

        assertThat(f.ran).containsExactly("pure:after"); // the poke never ran, on anyone
        assertThat(trace).filteredOn(event -> event.type() == TraceEvent.Type.STEP_FAILED)
                .extracting(TraceEvent::detail).singleElement().asString()
                .isEqualTo("action 'poke' failed: target player Ghost: no player named 'Ghost' is online");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
    }

    @Test
    void aRequireSeesAnUnresolvedTargetAndRunsItsElse() {
        resolution = spec -> TargetResolution.none("argument 'to' is not set");
        var interpreter = interpreter(true);
        FlowRun run = run(interpreter, rule("""
                - require:
                    - poke: {value: pay, target: {arg: to}}
                  else:
                    - a: refused
                - b: never
                """));

        assertThat(f.ran).containsExactly("a:refused");
        assertThat(run.outcome()).isEqualTo(RunOutcome.REQUIRE_FAILED);
    }

    @Test
    void oneSubjectFailingFailsTheStepButTheRestAreStillActedOn() {
        resolution = spec -> TargetResolution.of(List.of(mob("a"), mob("b"), mob("c")), "none");
        outcome = who -> who.endsWith("b") ? ActionOutcome.failed("refused") : ActionOutcome.OK;
        var interpreter = interpreter(true);
        run(interpreter, rule("- poke: {value: hit, target: {nearby: {radius: 4, limit: 3}}}"));

        assertThat(f.ran).hasSize(3);
        assertThat(trace).filteredOn(event -> event.type() == TraceEvent.Type.STEP_FAILED)
                .extracting(TraceEvent::detail).singleElement().asString()
                .startsWith("action 'poke' failed: entity b: refused")
                .endsWith("(on entity a, entity b, entity c)");
    }

    @Test
    void anEngineWithNoResolverFailsTheStepWithAReason() {
        var interpreter = interpreter(false);
        run(interpreter, rule("- poke: {value: hi, target: {arg: to}}\n- pure: after"));

        assertThat(f.ran).containsExactly("pure:after");
        assertThat(trace).filteredOn(event -> event.type() == TraceEvent.Type.STEP_FAILED)
                .extracting(TraceEvent::detail).singleElement().asString().contains("targets are not available here");
    }

    @Test
    void aSubjectThatLeavesBeforeTheActionRanIsThatSubjectsFailureNotAThrownError() {
        resolution = spec -> TargetResolution.of(player("Bob"));
        var interpreter = interpreter(true);
        FlowRun run = interpreter.start(rule("- poke: {value: hi, target: {arg: to}}\n- pure: after"),
                new MapLogicContext(Map.of("vars", new java.util.LinkedHashMap<String, Object>())),
                RunOwner.of("u1", RUN));

        regions.retireAll(); // the hop to Bob's region is dropped: he quit
        regions.drain();

        assertThat(f.ran).containsExactly("pure:after");
        assertThat(trace).filteredOn(event -> event.type() == TraceEvent.Type.STEP_FAILED)
                .extracting(TraceEvent::detail).singleElement().asString()
                .isEqualTo("action 'poke' failed: player Bob left before the action ran (on player Bob)");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
        // an unguarded failure is the engine's ordinary rate-limited warning (MTR-0023), as for any action
        assertThat(diagnostics).singleElement().satisfies(d -> assertThat(d.code().code()).isEqualTo("MTR-0023"));
    }

    // --- the context ---------------------------------------------------------------------

    @Test
    void theTargetedContextAddsTheFacetAndLeavesEverythingElseTheRuns() {
        var base = new MapLogicContext(Map.of("player", Map.of("name", "Alex"), "vars", new java.util.LinkedHashMap<String, Object>()))
                .withPlaceholders(text -> text.replace("%who%", "run-player"));
        var subject = player("Bob");
        var targeted = new TargetedContext(base, subject);

        assertThat(targeted.facet(ResolvedTarget.FACET)).containsSame(subject);
        assertThat(base.facet(ResolvedTarget.FACET)).isEmpty();
        assertThat(targeted.value("player.name")).isEqualTo("Alex"); // the sender's name, not the target's
        assertThat(targeted.applyPlaceholders("%who%")).isEqualTo("run-player");
        targeted.locals().put("k", 1);
        assertThat(base.locals()).containsEntry("k", 1);
    }

    // --- load ----------------------------------------------------------------------------

    @Test
    void aTargetOnAnActionThatCannotTakeOneIsRejectedAtLoad() {
        interpreter(true);
        ParsedRules parsed = f.compile("- on: click\n  do:\n    - a: {value: x, target: self}\n    - pure: ok");
        assertThat(parsed.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0057");
            assertThat(problem.message()).contains("this action cannot act on a target");
        });
        assertThat(parsed.rules()).singleElement().satisfies(rule -> assertThat(rule.steps()).hasSize(1));
    }

    @Test
    void aBadTargetSkipsThatStepAndTheEventTargetNeedsATriggerThatCarriesOne() {
        interpreter(true);
        ParsedRules parsed = f.compile("""
                - on: click
                  do:
                    - poke: {value: x, target: {nearby: {radius: 500}}}
                    - poke: {value: y, target: event.target}
                    - poke: {value: z, target: {arg: ok}}
                """);
        assertThat(parsed.problems()).extracting(problem -> problem.message()).anySatisfy(message -> assertThat(message)
                .contains("radius must be a finite number in 0.5..64"))
                .anySatisfy(message -> assertThat(message).contains("event.target needs a trigger that carries an entity"));
        assertThat(parsed.rules()).singleElement().satisfies(rule -> assertThat(rule.steps()).hasSize(1));
        var kept = (ActionStep<?>) parsed.rules().get(0).steps().get(0);
        assertThat(kept.target().spec()).isEqualTo(new TargetSpec.Arg("ok"));
        assertThat(kept.target().admits()).containsExactlyInAnyOrder(TargetKind.PLAYER, TargetKind.ENTITY);
    }
}
