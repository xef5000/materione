package dev.materione.api.scheduling;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A timer interval authored as whole server ticks or as a duration of at least one second. */
public record TimerInterval(Kind kind, long amount, String text) {

    private static final long MINIMUM_TICKS = 20;
    private static final long MINIMUM_MILLIS = 1_000;
    private static final Pattern COMPONENT = Pattern.compile("(\\d+)(ms|s|m|h|d)");

    public TimerInterval {
        Objects.requireNonNull(kind, "kind");
        text = text == null ? "" : text;
        if (kind == Kind.TICKS && amount < MINIMUM_TICKS) {
            throw new IllegalArgumentException("tick interval must be at least 20");
        }
        if (kind == Kind.DURATION && amount < MINIMUM_MILLIS) {
            throw new IllegalArgumentException("duration must be at least 1s");
        }
    }

    public static TimerInterval parse(Object raw) {
        if (raw instanceof Number number) {
            long ticks = wholeTicks(number);
            return new TimerInterval(Kind.TICKS, ticks, Long.toString(ticks));
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("write whole ticks (20 or more), or a duration such as 5m or 1h30m");
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.matches("\\d+")) {
            return parse(Long.parseLong(value));
        }
        Matcher matcher = COMPONENT.matcher(value);
        long millis = 0;
        int at = 0;
        while (matcher.find()) {
            if (matcher.start() != at) {
                throw new IllegalArgumentException("invalid duration '" + text + "'");
            }
            long quantity;
            try {
                quantity = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException overflow) {
                throw new IllegalArgumentException("duration is too large", overflow);
            }
            long factor = switch (matcher.group(2)) {
                case "ms" -> 1;
                case "s" -> 1_000;
                case "m" -> 60_000;
                case "h" -> 3_600_000;
                case "d" -> 86_400_000;
                default -> throw new IllegalStateException("unreachable duration unit");
            };
            try {
                millis = Math.addExact(millis, Math.multiplyExact(quantity, factor));
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("duration is too large", overflow);
            }
            at = matcher.end();
        }
        if (at != value.length()) {
            throw new IllegalArgumentException("invalid duration '" + text + "'");
        }
        return new TimerInterval(Kind.DURATION, millis, value);
    }

    public Duration duration() {
        if (kind != Kind.DURATION) {
            throw new IllegalStateException("a tick interval has no wall-clock duration");
        }
        return Duration.ofMillis(amount);
    }

    private static long wholeTicks(Number number) {
        try {
            BigDecimal value = new BigDecimal(number.toString());
            long ticks = value.longValueExact();
            if (ticks < MINIMUM_TICKS) {
                throw new IllegalArgumentException("tick interval must be at least 20 (one second)");
            }
            return ticks;
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("tick interval must be a whole number of at least 20", invalid);
        }
    }

    public enum Kind {
        TICKS,
        DURATION
    }
}
