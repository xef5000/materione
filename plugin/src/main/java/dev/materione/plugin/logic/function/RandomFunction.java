package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code random(min, max)}: a random number between the bounds. It is the one function that is not
 * a pure function of its arguments (logic.md §4.2). When both bounds are whole numbers the result is
 * a whole number in {@code [min, max]}, both ends included; otherwise it is a real in
 * {@code [min, max)}. Reversed bounds are swapped. Null for a non-number.
 */
public final class RandomFunction implements ExpressionFunction {

    /** Whole-number bounds beyond this magnitude answer null, so the integer range cannot overflow. */
    private static final double MAX_WHOLE_BOUND = 1e15;

    @Override
    public String id() {
        return "random";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Random",
                "A random number between the bounds: a whole number from min to max inclusive when both bounds "
                        + "are whole, otherwise a real from min up to max. Reversed bounds are swapped. "
                        + "This is the one function that is not pure: the same call can give different results.",
                List.of(
                        param("min", ParameterType.DECIMAL, true, "The lower bound"),
                        param("max", ParameterType.DECIMAL, true, "The upper bound")),
                FunctionArgs.SINCE), ParameterType.DECIMAL);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<Double> first = FunctionArgs.number(arguments.get(0));
        Optional<Double> second = FunctionArgs.number(arguments.get(1));
        if (first.isEmpty() || second.isEmpty()) {
            return null;
        }
        double low = Math.min(first.get(), second.get());
        double high = Math.max(first.get(), second.get());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (FunctionArgs.whole(low) && FunctionArgs.whole(high)
                && Math.abs(low) <= MAX_WHOLE_BOUND && Math.abs(high) <= MAX_WHOLE_BOUND) {
            return (double) random.nextLong((long) low, (long) high + 1);
        }
        double value = low + (high - low) * random.nextDouble();
        return Double.isFinite(value) ? value : null;
    }
}
