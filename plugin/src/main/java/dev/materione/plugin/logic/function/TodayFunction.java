package dev.materione.plugin.logic.function;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * {@code today()}: the local day number, the whole days since 1970-01-01 in the clock's zone (logic.md §15). The plugin
 * gives it the server's default zone, so the day changes at the server's local midnight. The value is the same for the
 * whole local day, which is what a daily reward compares a stored day number with.
 */
public final class TodayFunction implements ExpressionFunction {

    private final Clock clock;

    /** A function that reads the system clock in the server's default zone. */
    public TodayFunction() {
        this(Clock.systemDefaultZone());
    }

    /** @param clock the clock the function reads (its zone decides the day); injectable for tests */
    public TodayFunction(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String id() {
        return "today";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Today",
                "The server's local day number: the whole days since 1970-01-01 in the server's time zone. "
                        + "It is the same for the whole local day and changes at local midnight. It is not pure: "
                        + "it reads the clock.",
                List.of(), FunctionArgs.SINCE), ParameterType.INTEGER);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        return (double) LocalDate.now(clock).toEpochDay();
    }
}
