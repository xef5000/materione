package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;

/** {@code is_number(value)}: true for a finite number, or text that reads as one. */
public final class IsNumberFunction implements ExpressionFunction {

    @Override
    public String id() {
        return "is_number";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Is number",
                "True for a number, or for text that reads as a finite number such as \" 12.5 \" or \"-3e2\". "
                        + "Booleans and null are not numbers.",
                List.of(param("value", ParameterType.STRING, true, "The value to test")),
                FunctionArgs.SINCE), ParameterType.BOOLEAN);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        return FunctionArgs.number(arguments.get(0)).isPresent();
    }
}
