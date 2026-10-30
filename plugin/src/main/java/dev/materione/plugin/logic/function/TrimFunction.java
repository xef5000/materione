package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Optional;

/** {@code trim(text)}: the text without leading and trailing whitespace (Unicode whitespace), or null. */
public final class TrimFunction implements ExpressionFunction {

    @Override
    public String id() {
        return "trim";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Trim",
                "The text without leading and trailing whitespace. Null for a non-text argument.",
                List.of(param("text", ParameterType.STRING, true, "The text to trim")),
                FunctionArgs.SINCE), ParameterType.STRING);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<String> text = FunctionArgs.text(arguments.get(0));
        return text.map(String::strip).orElse(null);
    }
}
