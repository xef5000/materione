package dev.materione.plugin.logic.function;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * {@code now()}: the current time in whole epoch seconds (logic.md §15). It reads the clock it was given, which the
 * plugin sets to the system clock and a test sets to a fixed one, so a call is never evaluated at load or parse time.
 * The same call gives a later value each time it runs.
 */
public final class NowFunction implements ExpressionFunction {

    private final Clock clock;

    /** A function that reads the system clock. */
    public NowFunction() {
        this(Clock.systemDefaultZone());
    }

    /** @param clock the clock the function reads; injectable for tests */
    public NowFunction(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String id() {
        return "now";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Now",
                "The current time in whole epoch seconds (seconds since 1970-01-01 UTC, rounded down). "
                        + "It is not pure: the same call gives a later value each time it runs. "
                        + "Subtract a stored time from it to get the seconds that have passed.",
                List.of(), FunctionArgs.SINCE), ParameterType.INTEGER);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        return (double) Math.floorDiv(clock.millis(), 1000L);
    }
}
