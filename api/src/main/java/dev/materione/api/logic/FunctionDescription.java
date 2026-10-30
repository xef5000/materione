package dev.materione.api.logic;

import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.Objects;

/**
 * The description of an {@link ExpressionFunction}: display metadata whose parameter list is the
 * argument list, the kind of value it returns, and whether the last argument repeats.
 *
 * @param self display metadata; its parameters are the positional arguments in order, with
 *        {@code required} marking the ones a call must supply
 * @param returns the kind of value a call produces
 * @param variadic true when the last parameter may repeat ({@code min(a, b, c…)})
 */
public record FunctionDescription(SelfDescription self, ParameterType returns, boolean variadic) {

    public FunctionDescription {
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(returns, "returns");
        if (variadic && self.parameters().isEmpty()) {
            throw new IllegalArgumentException("a variadic function needs a parameter to repeat");
        }
    }

    /** A fixed-arity function. */
    public static FunctionDescription of(SelfDescription self, ParameterType returns) {
        return new FunctionDescription(self, returns, false);
    }

    /** The fewest arguments a call may pass. */
    public int minArguments() {
        int required = 0;
        for (var parameter : self.parameters()) {
            if (parameter.required()) {
                required++;
            }
        }
        return required;
    }

    /** The most arguments a call may pass, or {@code -1} when unbounded. */
    public int maxArguments() {
        return variadic ? -1 : self.parameters().size();
    }
}
