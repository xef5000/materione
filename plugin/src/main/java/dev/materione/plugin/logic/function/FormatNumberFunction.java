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
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code format_number(value)} or {@code format_number(value, decimals)}: the number with thousands
 * separators and a fixed number of decimal places (0 to 10), in the root locale so the output does
 * not depend on the server's language. Halves round away from zero. Null for a non-number or for
 * decimals outside that range.
 */
public final class FormatNumberFunction implements ExpressionFunction {

    private static final int MAX_DECIMALS = 10;

    @Override
    public String id() {
        return "format_number";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Format number",
                "Formats the number with thousands separators and a fixed number of decimal places (0 to 10), "
                        + "for example 1234.5 with 2 places is 1,234.50.",
                List.of(
                        param("value", ParameterType.DECIMAL, true, "The number to format"),
                        new ParameterSchema("decimals", ParameterType.INTEGER, false, "0",
                                "Decimal places to show", null, null)),
                FunctionArgs.SINCE), ParameterType.STRING);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<Double> value = FunctionArgs.number(arguments.get(0));
        if (value.isEmpty()) {
            return null;
        }
        int decimals = 0;
        if (arguments.size() > 1) {
            Optional<Double> requested = FunctionArgs.number(arguments.get(1));
            if (requested.isEmpty() || !FunctionArgs.whole(requested.get())
                    || requested.get() < 0 || requested.get() > MAX_DECIMALS) {
                return null;
            }
            decimals = requested.get().intValue();
        }
        String pattern = decimals == 0 ? "#,##0" : "#,##0." + "0".repeat(decimals);
        var format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(BigDecimal.valueOf(value.get()));
    }
}
