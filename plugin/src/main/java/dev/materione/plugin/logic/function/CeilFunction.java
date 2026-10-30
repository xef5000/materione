package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Optional;

/** {@code ceil(value)}: the smallest whole number not below the value, or null for a non-number. */
public final class CeilFunction implements ExpressionFunction {

    @Override
    public String id() {
        return "ceil";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Ceiling",
                "The smallest whole number not less than the value. Null for a non-number.",
                List.of(param("value", ParameterType.DECIMAL, true, "The number")),
                FunctionArgs.SINCE), ParameterType.DECIMAL);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<Double> value = FunctionArgs.number(arguments.get(0));
        return value.map(Math::ceil).orElse(null);
    }
}
