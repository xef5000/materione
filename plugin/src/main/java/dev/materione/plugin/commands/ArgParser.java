package dev.materione.plugin.commands;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Types the arguments a GUI is opened with (logic.md §6.1, stage 3): the raw text a command, the API or
 * {@code gui.open} passed, checked against the block's typed arguments. A missing argument takes its
 * default; a missing argument with no default is an error unless it is optional. An argument the block
 * does not declare is an error too, so a misspelt name is reported rather than ignored.
 *
 * <p>The first problem stops the parse and is the message the usage line follows. The result's values are
 * in the block's order, which is the order {@code gui.args.*} lists them in.
 */
public final class ArgParser {

    private ArgParser() {}

    /**
     * @param values the typed values, in the block's order, when the parse succeeded
     * @param error the first problem, or null
     */
    public record Result(Map<String, String> values, String error) {

        public Result {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Types {@code raw} against {@code plan}.
     *
     * @param raw the arguments as given, name to text
     * @param players the online players a {@code player} argument resolves against
     */
    public static Result parse(OpenPlan plan, Map<String, String> raw, PlayerNames players) {
        for (String name : raw.keySet()) {
            if (plan.arg(name).isEmpty()) {
                return refused("unknown argument '" + name + "'");
            }
        }
        var values = new LinkedHashMap<String, String>();
        for (OpenArgSpec arg : plan.args()) {
            String text = raw.get(arg.name());
            if (text == null) {
                if (arg.defaultValue() != null) {
                    values.put(arg.name(), arg.defaultValue());
                } else if (!arg.optional()) {
                    return refused("missing " + arg.name());
                }
                continue;
            }
            ArgType.Typed typed = arg.type().parse(text, arg.suggest(), players);
            if (!typed.ok()) {
                return refused(arg.name() + ": " + typed.error());
            }
            values.put(arg.name(), typed.value());
        }
        return new Result(values, null);
    }

    /**
     * The parsed values as the expression engine reads them (logic.md §40): a whole number as an integer, a number as
     * a double, {@code true} and {@code false} as booleans, everything else as the text it is. A GUI's
     * {@code gui.args} stay text, as they always were; a command document's {@code args.*} are these, so
     * {@code ${args.amount} > 0} compares numbers and {@code ${args.amount} * 2} does arithmetic.
     *
     * @param values the canonical text {@link #parse} returned
     */
    public static Map<String, Object> typed(OpenPlan plan, Map<String, String> values) {
        var out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String text = entry.getValue();
            ArgType type = plan.arg(entry.getKey()).map(OpenArgSpec::type).orElse(ArgType.STRING);
            Object value = text;
            try {
                value = switch (type) {
                    case INT -> (Object) Integer.valueOf(text);
                    case NUMBER -> (Object) Double.valueOf(text);
                    case BOOLEAN -> (Object) Boolean.valueOf(text);
                    default -> text;
                };
            } catch (NumberFormatException keptAsText) {
                // a default that does not read as its type was dropped at compile; text is the safe reading
            }
            out.put(entry.getKey(), value);
        }
        return out;
    }

    private static Result refused(String error) {
        return new Result(Map.of(), error);
    }
}
