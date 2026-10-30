package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Optional;

/** {@code ends_with(text, suffix)}: true when {@code text} ends with {@code suffix}, case-sensitively. */
public final class EndsWithFunction implements ExpressionFunction {

    @Override
    public String id() {
        return "ends_with";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Ends with",
                "True when the text ends with the suffix, case-sensitively. A non-text argument makes it false.",
                List.of(
                        param("text", ParameterType.STRING, true, "The text to test"),
                        param("suffix", ParameterType.STRING, true, "The suffix to look for")),
                FunctionArgs.SINCE), ParameterType.BOOLEAN);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<String> text = FunctionArgs.text(arguments.get(0));
        Optional<String> suffix = FunctionArgs.text(arguments.get(1));
        return text.isPresent() && suffix.isPresent() && text.get().endsWith(suffix.get());
    }
}
