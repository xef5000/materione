package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Optional;

/** {@code floor(value)}: the largest whole number not above the value, or null for a non-number. */
public final class FloorFunction implements ExpressionFunction {

    @Override
    public String id() {
        return "floor";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Floor",
                "The largest whole number not greater than the value. Null for a non-number.",
                List.of(param("value", ParameterType.DECIMAL, true, "The number")),
                FunctionArgs.SINCE), ParameterType.DECIMAL);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<Double> value = FunctionArgs.number(arguments.get(0));
        return value.map(Math::floor).orElse(null);
    }
}
