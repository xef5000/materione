package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Optional;

/** {@code length(text)}: the number of characters (Unicode code points) in the text, or null. */
public final class LengthFunction implements ExpressionFunction {

    @Override
    public String id() {
        return "length";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Length",
                "The number of characters in the text (Unicode code points). Null for a non-text argument.",
                List.of(param("text", ParameterType.STRING, true, "The text to measure")),
                FunctionArgs.SINCE), ParameterType.DECIMAL);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<String> text = FunctionArgs.text(arguments.get(0));
        if (text.isEmpty()) {
            return null;
        }
        String value = text.get();
        return (double) value.codePointCount(0, value.length());
    }
}
