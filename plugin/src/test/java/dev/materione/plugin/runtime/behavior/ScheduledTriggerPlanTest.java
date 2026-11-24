package dev.materione.plugin.runtime.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.api.scheduling.TimerInterval;
import dev.materione.behavior.flow.AllCond;
import dev.materione.behavior.flow.StepPath;
import dev.materione.behavior.rule.FilterFacts;
import dev.materione.behavior.rule.EventSelector;
import dev.materione.behavior.rule.EventTypes;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.RuleFilters;
import dev.materione.behavior.rule.RuleOptions;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.platform.scheduling.TimerWheel;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class ScheduledTriggerPlanTest {

    @Test
    void aPackWithNoTimersProducesNoRegistrations() {
        var problems = new ArrayList<Diagnostic>();
        WorldRules world = WorldRules.of(Map.of(), Map.of());

        assertThat(ScheduledTriggerPlan.of(world, problems::add)).isEmpty();
        assertThat(problems).isEmpty();
    }

    @Test
    void aPackIsCappedDeterministicallyAndTheExcessIsDiagnosedOnce() {
        var rules = new ArrayList<Rule>();
        for (int i = 0; i < ScheduledTriggerPlan.TIMERS_PER_PACK + 7; i++) {
            rules.add(timerRule("timer_" + i,
                    EventTypes.EVERY, Map.of("interval", List.of("1s"), "for", List.of("server"))));
        }
        WorldRules world = WorldRules.of(
                Map.of(NamespacedId.of("demo", "many"), rules), Map.of());
        var problems = new ArrayList<Diagnostic>();

        List<ScheduledTriggerPlan.Entry> plan = ScheduledTriggerPlan.of(world, problems::add);

        assertThat(plan).hasSize(ScheduledTriggerPlan.TIMERS_PER_PACK);
        assertThat(plan).extracting(entry -> entry.rule().id()).startsWith("timer_0")
                .endsWith("timer_" + (ScheduledTriggerPlan.TIMERS_PER_PACK - 1));
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(DiagnosticCodes.TIMER_BUDGET_EXCEEDED);
            assertThat(problem.message()).contains("only the first 128").contains("135 scheduled triggers");
        });
    }

    @Test
    void theBudgetRetainsTheFirstRegistrationsInAuthoredRuleOrderAcrossTimerTypes() {
        var rules = new ArrayList<Rule>();
        rules.add(timerRule("daily", EventTypes.SCHEDULE, Map.of("at", List.of("06:00"))));
        for (int i = 0; i < ScheduledTriggerPlan.TIMERS_PER_PACK; i++) {
            rules.add(timerRule("every_" + i, EventTypes.EVERY,
                    Map.of("interval", List.of("1s"), "for", List.of("server"))));
        }
        WorldRules world = WorldRules.of(
                Map.of(NamespacedId.of("demo", "mixed"), rules), Map.of());
        var problems = new ArrayList<Diagnostic>();

        List<ScheduledTriggerPlan.Entry> plan = ScheduledTriggerPlan.of(world, problems::add);

        assertThat(plan).extracting(entry -> entry.rule().id())
                .startsWith("daily")
                .doesNotContain("every_127");
        assertThat(problems).singleElement()
                .satisfies(problem -> assertThat(problem.message()).contains("129 scheduled triggers"));
    }

    @Test
    void aDailyTimerDriftedIntoAnotherDayStillMatchesTheDayItWasScheduledFor() throws Exception {
        var now = new AtomicReference<>(Instant.parse("2026-01-05T05:00:00Z"));
        Clock clock = clock(now);
        var service = new ScheduledTriggerService(
                proxy(Plugin.class), proxy(SchedulingGateway.class), null, null,
                RuntimeRegistries::empty, diagnostic -> {}, clock, ZoneOffset.UTC);
        Rule rule = timerRule("reset", EventTypes.SCHEDULE,
                Map.of("at", List.of("06:00"), "days", List.of("monday")));
        ScheduledTriggerPlan.Entry entry = ScheduledTriggerPlan.Entry.daily(
                rule, LocalTime.of(6, 0), ruleDays(rule), "06:00");
        var facts = new AtomicReference<FilterFacts>();
        var wheel = new TimerWheel(clock, ZoneOffset.UTC, failure -> {
            throw new AssertionError(failure);
        });
        wheel.replace(List.of(TimerWheel.Registration.daily(
                "monday-reset", LocalTime.of(6, 0), Set.of(DayOfWeek.MONDAY),
                scheduledFor -> facts.set(service.facts(entry, scheduledFor)))), 0);

        now.set(Instant.parse("2026-01-06T07:00:00Z")); // the Monday firing is first noticed on Tuesday
        wheel.tick(1);

        assertThat(facts.get()).isNotNull();
        assertThat(rule.filters().matches(facts.get())).isTrue();
    }

    @Test
    void eachPlayerFanoutIsBoundedWhenRegistrationsProduceMoreWorkThanItCanDrain() {
        int registrations = ScheduledTriggerPlan.TIMERS_PER_PACK;
        int players = 100;
        var pending = new ScheduledTriggerService.FanoutBuffer<Integer>(
                ScheduledTriggerService.PLAYER_FANOUT_CAPACITY);
        int rejected = 0;
        for (int tick = 0; tick < 40; tick++) {
            if (tick % 20 == 0) {
                for (int registration = 0; registration < registrations; registration++) {
                    int registrationBase = registration * players;
                    List<Integer> wave = java.util.stream.IntStream.range(0, players)
                            .map(value -> registrationBase + value)
                            .boxed().toList();
                    if (!pending.offerAll(wave)) {
                        rejected++;
                    }
                }
            }
            for (int ignored : pending.take(ScheduledTriggerService.PLAYER_FANOUT_PER_TICK)) {
                var lease = pending.lease();
                lease.complete();
            }
            assertThat(pending.admitted()).isLessThanOrEqualTo(pending.capacity());
        }

        assertThat(pending.admitted()).isLessThanOrEqualTo(ScheduledTriggerService.PLAYER_FANOUT_CAPACITY);
        assertThat(rejected).isGreaterThan(0);
        assertThat(ScheduledTriggerService.fanoutOverloadDiagnostic(rejected, rejected * players))
                .satisfies(problem -> {
                    assertThat(problem.code()).isEqualTo(DiagnosticCodes.TIMER_FANOUT_OVERLOADED);
                    assertThat(problem.message()).contains("whole firing wave").contains("admitted runs");
                    assertThat(problem.hint()).contains("reduce");
                });
    }

    @Test
    void anOverCapacityFiringIsRejectedWholeRatherThanPartiallyAdmitted() {
        var pending = new ScheduledTriggerService.FanoutBuffer<Integer>(5);
        assertThat(pending.offerAll(List.of(1, 2, 3))).isTrue();

        assertThat(pending.offerAll(List.of(4, 5, 6))).isFalse();
        assertThat(pending.take(10)).containsExactly(1, 2, 3);
    }

    @Test
    void queuedAndEntitySchedulerWorkShareOneFanoutCapacity() {
        var pending = new ScheduledTriggerService.FanoutBuffer<Integer>(4);
        assertThat(pending.offerAll(List.of(1, 2, 3, 4))).isTrue();
        var leases = pending.take(4).stream().map(ignored -> pending.lease()).toList();

        assertThat(pending.offerAll(List.of(5))).isFalse();
        assertThat(pending.size()).isZero();
        assertThat(pending.admitted()).isEqualTo(4);

        leases.get(0).complete();
        assertThat(pending.offerAll(List.of(5))).isTrue();
    }

    @Test
    void duplicateIntervalAndAudienceValuesProduceOneDistinctRegistration() {
        Rule repeated = timerRule("repeat", EventTypes.EVERY,
                Map.of("interval", List.of("1m", "60s"), "for", List.of("server", "server")));

        List<ScheduledTriggerPlan.Entry> plan = ScheduledTriggerPlan.of(
                WorldRules.of(Map.of(NamespacedId.of("demo", "clock"), List.of(repeated)), Map.of()),
                diagnostic -> {
                    throw new AssertionError(diagnostic);
                });

        assertThat(plan).hasSize(1);
    }

    @Test
    void intervalsAndSchedulesKeepTheirTypedValuesForTheWheel() {
        Rule every = timerRule("regen", EventTypes.EVERY,
                Map.of("interval", List.of("20", "5m"), "for", List.of("server", "each_player")));
        Rule schedule = timerRule("reset", EventTypes.SCHEDULE,
                Map.of("at", List.of("06:00"), "days", List.of("monday", "friday")));
        WorldRules world = WorldRules.of(
                Map.of(NamespacedId.of("demo", "clock"), List.of(every, schedule)), Map.of());

        List<ScheduledTriggerPlan.Entry> plan = ScheduledTriggerPlan.of(world, diagnostic -> {
            throw new AssertionError(diagnostic);
        });

        assertThat(plan).filteredOn(entry -> entry.rule() == every).hasSize(4)
                .allSatisfy(entry -> assertThat(entry.interval()).isNotNull());
        assertThat(plan).filteredOn(entry -> entry.interval() != null
                        && entry.interval().kind() == TimerInterval.Kind.TICKS)
                .extracting(ScheduledTriggerPlan.Entry::audience)
                .containsExactly(ScheduledTriggerPlan.Audience.SERVER, ScheduledTriggerPlan.Audience.EACH_PLAYER);
        ScheduledTriggerPlan.Entry daily = plan.stream().filter(entry -> entry.rule() == schedule)
                .findFirst().orElseThrow();
        assertThat(daily.at()).isEqualTo(LocalTime.of(6, 0));
        assertThat(daily.days()).containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
    }

    @Test
    void eachPlayerFanoutIsDrainedInBoundedGlobalTickBatches() {
        var queue = new ArrayDeque<Integer>();
        for (int i = 0; i < 70; i++) {
            queue.addLast(i);
        }

        assertThat(ScheduledTriggerService.takeFanout(queue, 32))
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 32).boxed().toList());
        assertThat(ScheduledTriggerService.takeFanout(queue, 32))
                .containsExactlyElementsOf(java.util.stream.IntStream.range(32, 64).boxed().toList());
        assertThat(ScheduledTriggerService.takeFanout(queue, 32))
                .containsExactly(64, 65, 66, 67, 68, 69);
        assertThat(queue).isEmpty();
    }

    private static Rule timerRule(String id, String trigger, Map<String, List<String>> filters) {
        return new Rule(
                NamespacedId.of("demo", id),
                id,
                new EventSelector(trigger, Optional.empty()),
                new AllCond(List.of()),
                List.of(),
                RuleOptions.none(),
                new RuleFilters(new LinkedHashMap<>(filters)),
                StepPath.of("behavior", "0"));
    }

    private static java.util.Set<DayOfWeek> ruleDays(Rule rule) {
        return rule.filters().get("days").orElseThrow().stream()
                .map(value -> DayOfWeek.valueOf(value.toUpperCase(java.util.Locale.ROOT)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Clock clock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return zone.equals(getZone()) ? this : Clock.fixed(now.get(), zone);
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (instance, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> true;
                    case "hashCode" -> System.identityHashCode(instance);
                    case "equals" -> instance == args[0];
                    case "toString" -> "Fake" + type.getSimpleName();
                    default -> null;
                });
    }

}
