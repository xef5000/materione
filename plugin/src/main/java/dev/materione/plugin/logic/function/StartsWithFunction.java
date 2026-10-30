package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Optional;

/** {@code starts_with(text, prefix)}: true when {@code text} begins with {@code prefix}, case-sensitively. */
public final class StartsWithFunction implements ExpressionFunction {

    @Override
    public String id() {
        return "starts_with";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Starts with",
                "True when the text begins with the prefix, case-sensitively. A non-text argument makes it false.",
                List.of(
                        param("text", ParameterType.STRING, true, "The text to test"),
                        param("prefix", ParameterType.STRING, true, "The prefix to look for")),
                FunctionArgs.SINCE), ParameterType.BOOLEAN);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<String> text = FunctionArgs.text(arguments.get(0));
        Optional<String> prefix = FunctionArgs.text(arguments.get(1));
        return text.isPresent() && prefix.isPresent() && text.get().startsWith(prefix.get());
    }
}
