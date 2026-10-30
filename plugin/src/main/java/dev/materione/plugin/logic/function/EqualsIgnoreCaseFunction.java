package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Optional;

/** {@code equals_ignore_case(a, b)}: true when the two texts are equal ignoring case. */
public final class EqualsIgnoreCaseFunction implements ExpressionFunction {

    @Override
    public String id() {
        return "equals_ignore_case";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Equals ignoring case",
                "True when both texts are equal, ignoring case. A non-text argument makes it false.",
                List.of(
                        param("a", ParameterType.STRING, true, "The first text"),
                        param("b", ParameterType.STRING, true, "The second text")),
                FunctionArgs.SINCE), ParameterType.BOOLEAN);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<String> a = FunctionArgs.text(arguments.get(0));
        Optional<String> b = FunctionArgs.text(arguments.get(1));
        return a.isPresent() && b.isPresent() && a.get().equalsIgnoreCase(b.get());
    }
}
