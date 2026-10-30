package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * {@code round(value)} or {@code round(value, places)}: rounds to the nearest whole number, or to
 * {@code places} decimal places (0 to 15). Halves round away from zero, so {@code round(-2.5)} is -3.
 * Null for a non-number or for places outside that range.
 */
public final class RoundFunction implements ExpressionFunction {

    private static final int MAX_PLACES = 15;

    @Override
    public String id() {
        return "round";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Round",
                "Rounds to the nearest whole number, or to the given number of decimal places (0 to 15). "
                        + "Halves round away from zero.",
                List.of(
                        param("value", ParameterType.DECIMAL, true, "The number to round"),
                        new ParameterSchema(
                                "places", ParameterType.INTEGER, false, "0",
                                "Decimal places to keep", null, null)),
                FunctionArgs.SINCE), ParameterType.DECIMAL);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<Double> value = FunctionArgs.number(arguments.get(0));
        if (value.isEmpty()) {
            return null;
        }
        int places = 0;
        if (arguments.size() > 1) {
            Optional<Double> requested = FunctionArgs.number(arguments.get(1));
            if (requested.isEmpty() || !FunctionArgs.whole(requested.get())
                    || requested.get() < 0 || requested.get() > MAX_PLACES) {
                return null;
            }
            places = requested.get().intValue();
        }
        return BigDecimal.valueOf(value.get()).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }
}
