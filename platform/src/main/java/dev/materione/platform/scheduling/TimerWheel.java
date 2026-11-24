package dev.materione.platform.scheduling;

import dev.materione.api.scheduling.TimerInterval;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** A fake-clock-testable wheel for one global server scheduler. */
public final class TimerWheel {

    private final Clock clock;
    private final ZoneId zone;
    private final Consumer<Throwable> failures;
    private List<Arm> arms = List.of();

    public TimerWheel(Clock clock, ZoneId zone, Consumer<Throwable> failures) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.failures = failures == null ? ignored -> {} : failures;
    }

    public void replace(List<Registration> registrations, long currentTick) {
        Objects.requireNonNull(registrations, "registrations");
        Instant now = clock.instant();
        var next = new ArrayList<Arm>(registrations.size());
        for (Registration registration : registrations) {
            next.add(new Arm(registration, nextDue(registration, now, currentTick)));
        }
        arms = List.copyOf(next);
    }

    public void tick(long currentTick) {
        Instant now = clock.instant();
        for (Arm arm : arms) {
            Deadline deadline = arm.deadline;
            if (!deadline.due(now, currentTick)) {
                continue;
            }
            try {
                Runnable action = arm.registration.action();
                if (action instanceof TimedAction timed) {
                    timed.accept(deadline.wall);
                } else {
                    action.run();
                }
            } catch (Throwable t) {
                failures.accept(t);
            }
            arm.deadline = following(arm.registration, deadline, clock.instant(), currentTick);
        }
    }

    public int size() {
        return arms.size();
    }

    private Deadline nextDue(Registration registration, Instant now, long currentTick) {
        if (registration.interval != null) {
            return registration.interval.kind() == TimerInterval.Kind.TICKS
                    ? Deadline.tick(add(currentTick, registration.interval.amount()))
                    : Deadline.wall(now.plus(registration.interval.duration()));
        }
        return Deadline.wall(nextWallOccurrence(now, registration.at, registration.days));
    }

    private Deadline following(
            Registration registration, Deadline previous, Instant now, long currentTick) {
        if (registration.interval == null) {
            return Deadline.wall(nextWallOccurrence(now, registration.at, registration.days));
        }
        if (registration.interval.kind() == TimerInterval.Kind.TICKS) {
            long period = registration.interval.amount();
            long missed = currentTick - previous.tick;
            long skipped = missed / period + 1;
            return Deadline.tick(add(previous.tick, multiply(period, skipped)));
        }
        long period = registration.interval.duration().toMillis();
        long elapsed = Math.max(0, now.toEpochMilli() - previous.wall.toEpochMilli());
        long skipped = elapsed / period + 1;
        return Deadline.wall(previous.wall.plusMillis(multiply(period, skipped)));
    }

    private Instant nextWallOccurrence(Instant after, LocalTime at, Set<DayOfWeek> days) {
        var date = after.atZone(zone).toLocalDate();
        for (int offset = 0; offset < 8; offset++) {
            var candidateDate = date.plusDays(offset);
            if (days.contains(candidateDate.getDayOfWeek())) {
                Instant candidate = candidateDate.atTime(at).atZone(zone).toInstant();
                if (candidate.isAfter(after)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("no daily occurrence within a week");
    }

    private static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long multiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    public record Registration(
            String id,
            TimerInterval interval,
            LocalTime at,
            Set<DayOfWeek> days,
            Runnable action) {

        public Registration {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(action, "action");
            if ((interval == null) == (at == null)) {
                throw new IllegalArgumentException("a timer registration is an interval or a daily time, not both");
            }
            days = days == null || days.isEmpty() ? EnumSet.allOf(DayOfWeek.class) : Set.copyOf(days);
        }

        public static Registration every(String id, TimerInterval interval, Runnable action) {
            return new Registration(id, interval, null, Set.of(), action);
        }

        public static Registration daily(
                String id, LocalTime at, Set<DayOfWeek> days, Runnable action) {
            return new Registration(id, null, at, days, action);
        }

        public static Registration daily(
                String id, LocalTime at, Set<DayOfWeek> days, Consumer<Instant> action) {
            return new Registration(id, null, at, days, new ConsumerAction(action));
        }
    }

    private interface TimedAction extends Runnable {
        void accept(Instant scheduledFor);

        @Override
        default void run() {
            accept(null);
        }
    }

    private record ConsumerAction(Consumer<Instant> consumer) implements TimedAction {
        private ConsumerAction {
            Objects.requireNonNull(consumer, "consumer");
        }

        @Override
        public void accept(Instant scheduledFor) {
            consumer.accept(scheduledFor);
        }
    }

    private static final class Arm {
        private final Registration registration;
        private Deadline deadline;

        private Arm(Registration registration, Deadline deadline) {
            this.registration = registration;
            this.deadline = deadline;
        }
    }

    private record Deadline(long tick, Instant wall) {
        private static Deadline tick(long tick) {
            return new Deadline(tick, null);
        }

        private static Deadline wall(Instant wall) {
            return new Deadline(0, Objects.requireNonNull(wall, "wall"));
        }

        private boolean due(Instant now, long currentTick) {
            return wall == null ? currentTick >= tick : !now.isBefore(wall);
        }
    }
}
