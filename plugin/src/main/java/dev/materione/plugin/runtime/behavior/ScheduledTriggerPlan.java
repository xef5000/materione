package dev.materione.plugin.runtime.behavior;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.scheduling.TimerInterval;
import dev.materione.behavior.rule.EventTypes;
import dev.materione.behavior.rule.Rule;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** The deterministic, budgeted timer registrations derived from one applied rule-document shadow. */
final class ScheduledTriggerPlan {

    static final int TIMERS_PER_PACK = 128;

    private ScheduledTriggerPlan() {}

    static List<Entry> of(WorldRules world, Consumer<Diagnostic> report) {
        if (world == null) {
            return List.of();
        }
        var entries = new ArrayList<Entry>();
        var counts = new LinkedHashMap<String, Integer>();
        var skipped = new LinkedHashMap<String, Integer>();
        var firstExcess = new LinkedHashMap<String, Rule>();
        addEvery(world.forTrigger(EventTypes.EVERY), entries, counts, skipped, firstExcess, report);
        addSchedule(world.forTrigger(EventTypes.SCHEDULE), entries, counts, skipped, firstExcess, report);
        for (var excess : skipped.entrySet()) {
            Rule example = firstExcess.get(excess.getKey());
            report.accept(new Diagnostic(
                    DiagnosticCodes.TIMER_BUDGET_EXCEEDED, Severity.WARN,
                    "pack " + excess.getKey() + " declared " + (counts.get(excess.getKey()) + excess.getValue())
                            + " scheduled triggers; only the first " + TIMERS_PER_PACK + " were scheduled",
                    example == null ? null : example.owner(), null,
                    "reduce, split or remove timers in the pack; the excess " + excess.getValue()
                            + " trigger(s) do not run until reload"));
        }
        return List.copyOf(entries);
    }

    private static void addEvery(
            List<Rule> rules,
            List<Entry> entries,
            Map<String, Integer> counts,
            Map<String, Integer> skipped,
            Map<String, Rule> firstExcess,
            Consumer<Diagnostic> report) {
        for (Rule rule : rules) {
            List<String> intervals = rule.filters().get("interval").orElse(List.of());
            List<String> audiences = rule.filters().get("for").orElse(List.of("server"));
            for (String rawInterval : intervals) {
                TimerInterval interval;
                try {
                    interval = TimerInterval.parse(rawInterval);
                } catch (IllegalArgumentException e) {
                    invalid(rule, "invalid interval '" + rawInterval + "'", e, report);
                    continue;
                }
                for (String rawAudience : audiences) {
                    Audience audience = "each_player".equals(rawAudience) ? Audience.EACH_PLAYER : Audience.SERVER;
                    admit(entries, counts, skipped, firstExcess,
                            Entry.every(rule, interval, audience, rawInterval, rawAudience));
                }
            }
        }
    }

    private static void addSchedule(
            List<Rule> rules,
            List<Entry> entries,
            Map<String, Integer> counts,
            Map<String, Integer> skipped,
            Map<String, Rule> firstExcess,
            Consumer<Diagnostic> report) {
        for (Rule rule : rules) {
            List<String> times = rule.filters().get("at").orElse(List.of());
            Set<DayOfWeek> days = parseDays(rule.filters().get("days").orElse(List.of()));
            for (String rawTime : times) {
                LocalTime at;
                try {
                    at = LocalTime.parse(rawTime);
                } catch (RuntimeException e) {
                    invalid(rule, "invalid at time '" + rawTime + "'", e, report);
                    continue;
                }
                admit(entries, counts, skipped, firstExcess, Entry.daily(rule, at, days, rawTime));
            }
        }
    }

    private static void admit(
            List<Entry> entries,
            Map<String, Integer> counts,
            Map<String, Integer> skipped,
            Map<String, Rule> firstExcess,
            Entry entry) {
        String pack = entry.rule.owner().namespace();
        int current = counts.getOrDefault(pack, 0);
        if (current >= TIMERS_PER_PACK) {
            skipped.merge(pack, 1, Integer::sum);
            firstExcess.putIfAbsent(pack, entry.rule);
            return;
        }
        counts.put(pack, current + 1);
        entries.add(entry);
    }

    private static Set<DayOfWeek> parseDays(List<String> days) {
        if (days.isEmpty()) {
            return EnumSet.allOf(DayOfWeek.class);
        }
        var out = EnumSet.noneOf(DayOfWeek.class);
        for (String day : days) {
            out.add(DayOfWeek.valueOf(day.toUpperCase(Locale.ROOT)));
        }
        return Set.copyOf(out);
    }

    private static void invalid(Rule rule, String problem, RuntimeException cause, Consumer<Diagnostic> report) {
        report.accept(new Diagnostic(
                DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN,
                rule.owner() + " rule '" + rule.id() + "': " + problem + " (" + cause.getMessage() + ")",
                rule.owner(), null, "fix the timer filter and reload", rule.path().target()));
    }

    record Entry(
            Rule rule,
            TimerInterval interval,
            LocalTime at,
            Set<DayOfWeek> days,
            Audience audience,
            String value,
            String audienceValue) {

        Entry {
            days = days == null ? Set.of() : Set.copyOf(days);
        }

        static Entry every(
                Rule rule, TimerInterval interval, Audience audience, String value, String audienceValue) {
            return new Entry(rule, interval, null, Set.of(), audience, value, audienceValue);
        }

        static Entry daily(Rule rule, LocalTime at, Set<DayOfWeek> days, String value) {
            return new Entry(rule, null, at, days, Audience.SERVER, value, "server");
        }
    }

    enum Audience {
        SERVER,
        EACH_PLAYER
    }
}
