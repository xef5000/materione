package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** {@code lower(text)}: the text in lower case (root locale), or null. */
public final class LowerFunction implements ExpressionFunction {

    @Override
    public String id() {
        return "lower";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Lower case",
                "The text in lower case, using the root locale so the result never depends on the server's language. "
                        + "Null for a non-text argument.",
                List.of(param("text", ParameterType.STRING, true, "The text to convert")),
                FunctionArgs.SINCE), ParameterType.STRING);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<String> text = FunctionArgs.text(arguments.get(0));
        return text.map(value -> value.toLowerCase(Locale.ROOT)).orElse(null);
    }
}
