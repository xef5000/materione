package dev.materione.plugin.logic.trace;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.trace.TraceBatch;
import dev.materione.api.logic.trace.TraceEntry;
import dev.materione.api.logic.trace.TraceFilter;
import dev.materione.api.logic.trace.TraceKind;
import dev.materione.api.logic.trace.TraceLimits;
import dev.materione.api.registry.SelfDescription;
import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.runtime.BehaviorEvent;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.logic.LogicService;
import dev.materione.plugin.logic.LogicTestSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The live trace end to end, headless (logic.md §7.6, §32): a rule compiled from YAML by the real compiler is fired
 * through the real rule engine of a real {@link LogicService}, and a subscription made through
 * {@code api.logic().traces()} hears its step paths on the pump's tick, exactly as the studio's stream does. A click
 * storm proves the rate limit holds and that the engine runs every flow regardless; with nobody subscribed the
 * interpreter has no sink at all.
 */
class LogicTraceIntegrationTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final NamespacedId SHOP = NamespacedId.of("demo", "shop");

    private static final String RULES = """
            - "on": click
              do:
                - tick
                - if: "player.level > 5"
                  then: [ tick, refuse ]
                  else: [ tick ]
                - tick
            """;

    private final AtomicInteger ticks = new AtomicInteger();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<TraceBatch> heard = new ArrayList<>();
    private LogicService logic;
    private List<dev.materione.behavior.rule.Rule> rules;

    /** Counts how often it ran. */
    private final class Tick implements ActionType<String> {
        private final String id;
        private final ActionOutcome outcome;

        Tick(String id, ActionOutcome outcome) {
            this.id = id;
            this.outcome = outcome;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ActionDescription describe() {
            return ActionDescription.of(SelfDescription.of(id, "Counts"), LogicCategory.DATA);
        }

        @Override
        public Threading threading() {
            return Threading.ANY;
        }

        @Override
        public String parse(ParamReader params) {
            return "";
        }

        @Override
        public ActionOutcome execute(String params, LogicContext context) {
            ticks.incrementAndGet();
            return outcome;
        }
    }

    /** A gateway that only remembers what is scheduled ahead, so the test is the server tick. */
    private final SchedulingGateway gateway = new SchedulingGateway() {
        @Override
        public void executeSync(Plugin plugin, Location location, Runnable task) {
            task.run();
        }

        @Override
        public void executeEntity(Plugin plugin, Entity entity, Runnable task) {
            task.run();
        }

        @Override
        public void executeAsync(Plugin plugin, Runnable task) {
            task.run();
        }

        @Override
        public void executeGlobal(Plugin plugin, Runnable task) {
            task.run();
        }

        @Override
        public ScheduledHandle executeGlobalLater(Plugin plugin, long delayTicks, Runnable task) {
            scheduled.add(task);
            return () -> scheduled.remove(task);
        }
    };

    @BeforeEach
    void service() {
        logic = LogicTestSupport.newRunningService(new RegistryService(), gateway);
        LogicTestSupport.registerTriggers(logic);
        var owner = LogicTestSupport.owner("materione");
        logic.actions().register(owner, NamespacedId.of("materione", "tick"), new Tick("tick", ActionOutcome.OK));
        logic.actions().register(owner, NamespacedId.of("materione", "refuse"),
                new Tick("refuse", ActionOutcome.failed("not enough money")));
        var parsed = FlowCompiler.compile(SHOP, new org.yaml.snakeyaml.Yaml().load(RULES), Map.of(), logic.lookup());
        assertThat(parsed.problems()).isEmpty();
        rules = parsed.rules();
    }

    private void click(UUID player) {
        var who = new LinkedHashMap<String, Object>();
        who.put("level", 12);
        who.put("uuid", player.toString());
        var root = new LinkedHashMap<String, Object>();
        root.put("player", who);
        root.put("vars", new LinkedHashMap<String, Object>());
        logic.engine().fire(rules, new BehaviorEvent("click", "right", new MapLogicContext(root), null, null));
    }

    /** One server tick of the trace pump, as the scheduler would run it. */
    private void tick() {
        var due = List.copyOf(scheduled);
        scheduled.clear();
        due.forEach(Runnable::run);
    }

    @Test
    void aClickProducesATraceWithTheStepPathsOfThatRun() {
        var subscription = logic.traces().subscribe(TraceFilter.resource(SHOP.toString(), ALICE), heard::add);
        assertThat(subscription.ok()).isTrue();

        click(ALICE);
        tick();

        assertThat(heard).hasSize(1);
        List<TraceEntry> entries = heard.get(0).entries();
        assertThat(entries).extracting(TraceEntry::path).containsExactly(
                "behavior.0",
                "behavior.0.do.0",
                "behavior.0.do.1",
                "behavior.0.do.1.then.0",
                "behavior.0.do.1.then.1",
                "behavior.0.do.2",
                "behavior.0");
        assertThat(entries).extracting(TraceEntry::kind).containsExactly(
                TraceKind.RUN_STARTED, TraceKind.RAN, TraceKind.BRANCH, TraceKind.RAN, TraceKind.FAILED,
                TraceKind.RAN, TraceKind.RUN_ENDED);
        assertThat(entries.get(2).detail()).isEqualTo("then");
        assertThat(entries.get(4).detail()).contains("not enough money");
        assertThat(entries).extracting(TraceEntry::resource).containsOnly(SHOP.toString());
        assertThat(entries).extracting(TraceEntry::runId).containsOnly(entries.get(0).runId());
    }

    @Test
    void anotherPlayersClickIsNotTraced() {
        logic.traces().subscribe(TraceFilter.resource(SHOP.toString(), ALICE), heard::add);

        click(UUID.fromString("00000000-0000-0000-0000-0000000000b0"));
        tick();

        assertThat(heard).isEmpty();
        assertThat(ticks.get()).isEqualTo(4); // the flow itself ran all the same
    }

    @Test
    void nobodySubscribedMeansNoSinkAndNothingScheduled() {
        click(ALICE);

        assertThat(scheduled).isEmpty();
        assertThat(ticks.get()).isEqualTo(4);
        assertThat(heard).isEmpty();
    }

    @Test
    void theSinkAndThePumpFollowTheFirstAndLastSubscription() {
        var first = logic.traces().subscribe(TraceFilter.resource(SHOP.toString()), heard::add).value();
        var second = logic.traces().subscribe(TraceFilter.resource("demo:other"), heard::add).value();
        assertThat(scheduled).hasSize(1); // one pump, not one per subscription

        first.close();
        assertThat(scheduled).hasSize(1);
        second.close();
        assertThat(scheduled).isEmpty(); // the last one stops it

        click(ALICE);
        assertThat(heard).isEmpty();
        assertThat(scheduled).isEmpty();
    }

    @Test
    void thePumpKeepsTickingWhileSomeoneListens() {
        logic.traces().subscribe(TraceFilter.resource(SHOP.toString()), heard::add);

        tick();
        tick();
        click(ALICE);
        tick();

        assertThat(scheduled).hasSize(1);
        assertThat(heard).hasSize(1);
    }

    @Test
    void aClickStormIsHeldToTheRateLimitAndTheEngineRunsEveryFlow() {
        var limits = TraceLimits.DEFAULT;
        logic.traces().subscribe(TraceFilter.resource(SHOP.toString(), ALICE), heard::add);
        int clicks = 2_000;

        for (int i = 0; i < clicks; i++) {
            click(ALICE);
        }
        // the whole storm lands inside one pump interval; drain the way the server would, tick by tick
        for (int i = 0; i < 10; i++) {
            tick();
        }

        int delivered = heard.stream().mapToInt(batch -> batch.entries().size()).sum();
        int dropped = heard.stream().mapToInt(TraceBatch::dropped).sum();
        // 7 entries a click: the burst (one second's worth) plus whatever the milliseconds the storm took refilled
        assertThat(delivered).isLessThanOrEqualTo(limits.eventsPerSecond() * 3);
        assertThat(delivered + dropped).isEqualTo(clicks * 7);
        assertThat(dropped).isGreaterThan(0);
        assertThat(heard).allSatisfy(batch -> assertThat(batch.entries().size()).isLessThanOrEqualTo(limits.maxBatch()));
        // the engine was never slowed or failed by the subscriber: every click ran all of its four actions
        assertThat(ticks.get()).isEqualTo(clicks * 4);
    }
}
