package dev.materione.plugin.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
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
import dev.materione.api.registry.SelfDescription;
import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.behavior.flow.ActionStep;
import dev.materione.behavior.flow.AllCond;
import dev.materione.behavior.flow.StepPath;
import dev.materione.behavior.flow.StepTarget;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.rule.EventSelector;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.RuleFilters;
import dev.materione.behavior.rule.RuleOptions;
import dev.materione.behavior.run.FlowInterpreter;
import dev.materione.behavior.run.RunBudget;
import dev.materione.behavior.run.RunOutcome;
import dev.materione.behavior.run.RunOwner;
import dev.materione.behavior.run.TraceEvent;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * The Folia dispatch seam of target addressing (logic.md §39, D8), proven without a Folia server: the real
 * {@link GatewayRunScheduler} and the real interpreter over a gateway that models regions (a thread owns exactly one
 * entity, and a task submitted for another entity runs only when that entity's thread is drained). It proves where
 * work is <em>submitted</em>, which is everything the engine decides; that a real Folia region runs it there is the
 * server's guarantee, and nothing here was run on one.
 */
class TargetedDispatchTest {

    private final List<String> submitted = new ArrayList<>();
    private final List<String> effects = new ArrayList<>();
    private final List<TraceEvent> trace = new ArrayList<>();
    private final Entity runPlayer = entity("runPlayer");
    private final Entity other = entity("other");
    private Entity current = runPlayer;

    private record Task(Entity entity, Runnable task, Runnable retired) {}

    private final Deque<Task> queue = new ArrayDeque<>();

    private final SchedulingGateway gateway = new SchedulingGateway() {
        @Override
        public void executeSync(Plugin p, Location location, Runnable task) {
            throw new AssertionError("a run never uses the location scheduler");
        }

        @Override
        public void executeEntity(Plugin p, Entity entity, Runnable task) {
            throw new AssertionError("a run always schedules with a delay");
        }

        @Override
        public void executeAsync(Plugin p, Runnable task) {
            throw new AssertionError("a run never leaves the game threads");
        }

        @Override
        public void executeGlobal(Plugin p, Runnable task) {
            throw new AssertionError("a run always schedules with a delay");
        }

        @Override
        public ScheduledHandle executeEntityLater(Plugin p, Entity entity, long delay, Runnable task, Runnable retired) {
            submitted.add("entity:" + entity);
            var queued = new Task(entity, task, retired);
            queue.add(queued);
            return () -> queue.remove(queued);
        }

        @Override
        public ScheduledHandle executeGlobalLater(Plugin p, long delay, Runnable task) {
            submitted.add("global");
            throw new AssertionError("these steps never need the global thread");
        }
    };

    private final GatewayRunScheduler scheduler = new GatewayRunScheduler(LogicTestSupport.owner("host"), gateway,
            new GatewayRunScheduler.ThreadProbe() {
                @Override
                public boolean ownsEntity(Entity entity) {
                    return entity == current;
                }

                @Override
                public boolean onGlobalThread() {
                    return false;
                }
            });

    private static Entity entity(String name) {
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[] {Entity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private record Subject(Entity entity) implements ResolvedTarget {
        @Override
        public Object anchor() {
            return entity;
        }

        @Override
        public TargetKind kind() {
            return TargetKind.ENTITY;
        }

        @Override
        public String describe() {
            return "entity " + entity;
        }
    }

    /** Records the thread it ran on: which entity's region the test was draining. */
    private final class Mark implements ActionType<String> {
        @Override
        public String id() {
            return "mark";
        }

        @Override
        public ActionDescription describe() {
            return new ActionDescription(SelfDescription.of("Mark", "Marks where it ran"), LogicCategory.PLAYER, null)
                    .withTargets(TargetKind.ENTITY);
        }

        @Override
        public Threading threading() {
            return Threading.PLAYER_REGION;
        }

        @Override
        public String parse(ParamReader params) {
            return "";
        }

        @Override
        public ActionOutcome execute(String params, LogicContext context) {
            effects.add("mark on " + context.facet(ResolvedTarget.FACET).map(ResolvedTarget::describe).orElse("self")
                    + " ran in " + current);
            return ActionOutcome.OK;
        }
    }

    private void drain() {
        while (!queue.isEmpty()) {
            Task next = queue.poll();
            Entity before = current;
            current = next.entity();
            try {
                next.task().run();
            } finally {
                current = before;
            }
        }
    }

    private Rule rule(TargetSpec spec) {
        var path = StepPath.of("dispatch");
        var step = new ActionStep<>(path, "mark", new Mark(), "", 1, 0).withTarget(spec, Set.of(TargetKind.ENTITY, TargetKind.PLAYER));
        return new Rule(NamespacedId.of("test", "dispatch"), "dispatch", new EventSelector("click", Optional.empty()),
                new AllCond(List.of()), List.of(step), RuleOptions.none(), RuleFilters.none(), path);
    }

    private FlowInterpreter interpreter(TargetResolver resolver) {
        var interpreter = new FlowInterpreter(scheduler, RunBudget.DEFAULT, diagnostic -> { });
        interpreter.traceTo(trace::add);
        interpreter.targetsFrom(resolver);
        return interpreter;
    }

    @Test
    void theEffectIsSubmittedToTheSubjectsRegionAndTheRunComesBackToItsOwn() {
        var interpreter = interpreter((spec, admits, context, reads) ->
                CompletableFuture.completedFuture(TargetResolution.of(new Subject(other))));

        var run = interpreter.start(rule(new TargetSpec.Arg("to")), new MapLogicContext(Map.of()), RunOwner.of("u1", runPlayer));

        // the run started on its own region; the subject belongs to another, so the effect did not run inline
        assertThat(effects).isEmpty();
        assertThat(submitted).containsExactly("entity:other");
        assertThat(run.isFinished()).isFalse();

        drain();

        assertThat(effects).containsExactly("mark on entity other ran in other");
        // and the continuation went back to the run's own region before the run ended
        assertThat(submitted).containsExactly("entity:other", "entity:runPlayer");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
        assertThat(trace).filteredOn(event -> event.type() == TraceEvent.Type.STEP_OK)
                .extracting(TraceEvent::detail).containsExactly("on entity other");
    }

    @Test
    void aSubjectInTheRunsOwnRegionIsActedOnInlineWithNoHop() {
        var interpreter = interpreter((spec, admits, context, reads) ->
                CompletableFuture.completedFuture(TargetResolution.of(new Subject(runPlayer))));

        var run = interpreter.start(rule(new TargetSpec.Arg("to")), new MapLogicContext(Map.of()), RunOwner.of("u1", runPlayer));

        assertThat(submitted).isEmpty();
        assertThat(effects).containsExactly("mark on entity runPlayer ran in runPlayer");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
    }

    @Test
    void theTargetIsResolvedOnTheRunsOwnRegionBeforeAnySubjectIsTouched() {
        // the step arrives on a thread that owns neither entity (another region, or the global thread)
        current = null;
        var resolvedOn = new ArrayList<String>();
        var interpreter = interpreter((spec, admits, context, reads) -> {
            resolvedOn.add(String.valueOf(current));
            return reads.on(Threading.PLAYER_REGION, () -> TargetResolution.of(new Subject(other)));
        });

        var run = interpreter.start(rule(new TargetSpec.Nearby(8, null, 1)), new MapLogicContext(Map.of()), RunOwner.of("u1", runPlayer));
        assertThat(resolvedOn).isEmpty(); // nothing was resolved from a thread that does not own the run
        assertThat(submitted).containsExactly("entity:runPlayer");

        drain();

        assertThat(resolvedOn).containsExactly("runPlayer");
        assertThat(effects).containsExactly("mark on entity other ran in other");
        assertThat(submitted).containsExactly("entity:runPlayer", "entity:other", "entity:runPlayer");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
    }

    @Test
    void aSetIsHandledOneRegionAtATimeInOrder() {
        var third = entity("third");
        var interpreter = interpreter((spec, admits, context, reads) -> CompletableFuture.completedFuture(
                TargetResolution.of(List.of(new Subject(other), new Subject(third)), "none")));

        var run = interpreter.start(rule(new TargetSpec.Nearby(8, null, 2)), new MapLogicContext(Map.of()), RunOwner.of("u1", runPlayer));
        assertThat(submitted).containsExactly("entity:other"); // the second is not submitted until the first is done

        drain();

        assertThat(effects).containsExactly("mark on entity other ran in other", "mark on entity third ran in third");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
    }

    @Test
    void aSubjectThatRetiresBeforeItsRegionRunsTheTaskIsThatSubjectsFailure() {
        var interpreter = interpreter((spec, admits, context, reads) ->
                CompletableFuture.completedFuture(TargetResolution.of(new Subject(other))));

        var run = interpreter.start(rule(new TargetSpec.Arg("to")), new MapLogicContext(Map.of()), RunOwner.of("u1", runPlayer));
        var dropped = new ArrayList<>(queue);
        queue.clear();
        dropped.forEach(task -> task.retired().run()); // the entity scheduler found the entity removed
        drain();

        assertThat(effects).isEmpty();
        assertThat(trace).filteredOn(event -> event.type() == TraceEvent.Type.STEP_FAILED)
                .extracting(TraceEvent::detail).singleElement().asString().contains("left before the action ran");
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
    }
}
