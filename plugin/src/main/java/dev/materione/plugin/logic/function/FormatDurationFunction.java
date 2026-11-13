package dev.materione.plugin.logic.function;

import static dev.materione.plugin.logic.function.FunctionArgs.param;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code format_duration(seconds)}: a short human duration (logic.md §15). The format is fixed:
 *
 * <ul>
 *   <li>The seconds round <b>up</b> to a whole number, so a wait that is still running never reads {@code 0s}:
 *       {@code 0.3} is {@code 1s}.</li>
 *   <li>Zero or a negative value is {@code 0s}.</li>
 *   <li>Units are {@code d}, {@code h}, {@code m} and {@code s}. The output names the <b>two largest units that are not
 *       zero</b>, in descending order, separated by one space: {@code 93784} is {@code 1d 2h}, {@code 200} is
 *       {@code 3m 20s}, {@code 45} is {@code 45s}, {@code 3600} is {@code 1h}, {@code 90061} is {@code 1d 1h}.</li>
 *   <li>Null for a value that is not a number.</li>
 * </ul>
 */
public final class FormatDurationFunction implements ExpressionFunction {

    /** Beyond this many seconds the value is capped, so the unit arithmetic cannot overflow. */
    private static final double MAX_SECONDS = 9.0e15;

    private static final long DAY = 86_400L;
    private static final long HOUR = 3_600L;
    private static final long MINUTE = 60L;

    @Override
    public String id() {
        return "format_duration";
    }

    @Override
    public FunctionDescription describe() {
        return FunctionDescription.of(new SelfDescription("Format duration",
                "A short duration from a number of seconds: 1d 2h, 3m 20s or 45s. Fractions round up, zero or less "
                        + "is 0s, and only the two largest non-zero units are shown.",
                List.of(param("seconds", ParameterType.DECIMAL, true, "The duration in seconds")),
                FunctionArgs.SINCE), ParameterType.STRING);
    }

    @Override
    public Object call(List<Object> arguments, LogicContext context) {
        Optional<Double> seconds = FunctionArgs.number(arguments.get(0));
        if (seconds.isEmpty()) {
            return null;
        }
        double value = seconds.get();
        if (value <= 0) {
            return "0s";
        }
        long total = (long) Math.ceil(Math.min(value, MAX_SECONDS));
        long[] parts = {total / DAY, total % DAY / HOUR, total % HOUR / MINUTE, total % MINUTE};
        String[] names = {"d", "h", "m", "s"};
        var shown = new ArrayList<String>(2);
        for (int i = 0; i < parts.length && shown.size() < 2; i++) {
            if (parts[i] != 0) {
                shown.add(parts[i] + names[i]);
            }
        }
        return String.join(" ", shown);
    }
}
