package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Optional;

/**
 * {@code matches(text, pattern)}: true when the whole text matches the regular expression. The
 * limits of {@link RegexGuard} apply: an over-long pattern or text, an invalid pattern, or a match
 * that exceeds its read budget all answer {@code false}.
 */
public final class MatchesFunction implements ExpressionFunction {

    @Override
    public String id() {
        return "matches";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Matches pattern",
                "True when the whole text matches the regular expression (Java syntax). Use .* at either end "
                        + "for a partial match. Patterns up to 256 characters and texts up to 1024 characters; "
                        + "anything beyond those limits, or an invalid pattern, is false.",
                List.of(
                        param("text", ParameterType.STRING, true, "The text to test"),
                        param("pattern", ParameterType.STRING, true, "The regular expression")),
                FunctionArgs.SINCE), ParameterType.BOOLEAN);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<String> text = FunctionArgs.text(arguments.get(0));
        Optional<String> pattern = FunctionArgs.text(arguments.get(1));
        return text.isPresent() && pattern.isPresent() && RegexGuard.fullMatch(pattern.get(), text.get());
    }
}
