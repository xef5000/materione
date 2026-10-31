package dev.materione.plugin.gui.open;

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

    private static Result refused(String error) {
        return new Result(Map.of(), error);
    }
}
