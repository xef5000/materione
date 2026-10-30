package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Optional;

/** {@code min(a, b, …)}: the smallest of the numbers, or null when any argument is not a number. */
public final class MinFunction implements ExpressionFunction {

    @Override
    public String id() {
        return "min";
    }

    @Override
    public FunctionDescription describe() {
        return new FunctionDescription(new SelfDescription("Minimum",
                "The smallest of the given numbers. Null when any argument is not a number.",
                List.of(param("values", ParameterType.DECIMAL, true, "Two or more numbers")),
                FunctionArgs.SINCE), ParameterType.DECIMAL, true);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Double least = null;
        for (Object argument : arguments) {
            Optional<Double> value = FunctionArgs.number(argument);
            if (value.isEmpty()) {
                return null;
            }
            least = least == null ? value.get() : Math.min(least, value.get());
        }
        return least;
    }
}
