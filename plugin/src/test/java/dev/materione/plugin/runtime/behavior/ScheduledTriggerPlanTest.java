package dev.materione.plugin.runtime.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.scheduling.TimerInterval;
import dev.materione.behavior.flow.AllCond;
import dev.materione.behavior.flow.StepPath;
import dev.materione.behavior.rule.EventSelector;
import dev.materione.behavior.rule.EventTypes;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.RuleFilters;
import dev.materione.behavior.rule.RuleOptions;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
}
