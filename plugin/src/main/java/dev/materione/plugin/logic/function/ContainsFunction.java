package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Optional;

/** {@code contains(text, part)}: true when {@code text} contains {@code part}, case-sensitively. */
public final class ContainsFunction implements ExpressionFunction {

    @Override
    public String id() {
        return "contains";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Contains",
                "True when the text contains the part, case-sensitively. A non-text argument makes it false.",
                List.of(
                        param("text", ParameterType.STRING, true, "The text to search"),
                        param("part", ParameterType.STRING, true, "The part to look for")),
                FunctionArgs.SINCE), ParameterType.BOOLEAN);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<String> text = FunctionArgs.text(arguments.get(0));
        Optional<String> part = FunctionArgs.text(arguments.get(1));
        return text.isPresent() && part.isPresent() && text.get().contains(part.get());
    }
}
