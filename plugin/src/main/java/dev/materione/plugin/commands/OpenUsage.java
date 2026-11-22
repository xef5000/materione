package dev.materione.plugin.commands;

import java.util.stream.Collectors;

/**
 * The usage line of an open command, generated from the block's typed arguments (logic.md §6.1): a
 * required argument in angle brackets, an optional one or one with a default in square brackets, a
 * {@code choice} with its values, a default after an equals sign. A player who gets it sees the shape
 * the command takes without reading the GUI's file.
 */
public final class OpenUsage {

    private OpenUsage() {}

    /**
     * The usage line for {@code prefix} (for example {@code /shop}), with nothing before it.
     *
     * @param prefix the command as typed, or {@code /materione gui open <id>} for the API
     */
    public static String of(String prefix, OpenPlan plan) {
        String tokens = plan.args().stream().map(OpenUsage::token).collect(Collectors.joining(" "));
        return "usage: " + prefix + (tokens.isEmpty() ? "" : " " + tokens);
    }

    /** A refusal that names the problem and then the usage line: {@code 'x' is not a whole number. usage: ...}. */
    public static String refusal(String prefix, OpenPlan plan, String error) {
        return error + ". " + of(prefix, plan);
    }

    /** The range of a bounded number: {@code (1..100)}, {@code (>=1)} or {@code (<=100)}; nothing when unbounded. */
    private static String range(OpenArgSpec arg) {
        if (arg.min() != null && arg.max() != null) {
            return "(" + ArgType.bound(arg.min()) + ".." + ArgType.bound(arg.max()) + ")";
        }
        if (arg.min() != null) {
            return "(>=" + ArgType.bound(arg.min()) + ")";
        }
        return arg.max() != null ? "(<=" + ArgType.bound(arg.max()) + ")" : "";
    }

    private static String token(OpenArgSpec arg) {
        String type = arg.type() == ArgType.CHOICE ? String.join("|", arg.suggest()) : arg.type().word();
        String body = arg.name() + ":" + type + range(arg) + (arg.defaultValue() == null ? "" : "=" + arg.defaultValue());
        return arg.required() ? "<" + body + ">" : "[" + body + "]";
    }
}
