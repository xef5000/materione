package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;

/** {@code number(value)}: the value as a number, or null when it does not read as one. */
public final class NumberFunction implements ExpressionFunction {

    @Override
    public String id() {
        return "number";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Number",
                "The value as a number: a number as is, or text that reads as one. Null otherwise, "
                        + "including for booleans and for infinity.",
                List.of(param("value", ParameterType.STRING, true, "The value to convert")),
                FunctionArgs.SINCE), ParameterType.DECIMAL);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        return FunctionArgs.number(arguments.get(0)).orElse(null);
    }
}
