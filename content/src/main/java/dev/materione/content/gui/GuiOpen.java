package dev.materione.content.gui;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.gui.GuiAuthoringApi.OpenArg;
import dev.materione.api.gui.GuiAuthoringApi.OpenSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code open:} block's shape (gui.md §6.1, logic.md §6.1): read from a GUI's authored mapping
 * into a typed {@link OpenSpec}, checked, and written back in the authored key order. The runtime
 * that acts on it is #215; this class only knows what a valid block looks like. A condition and the
 * steps of {@code deny} are checked for their outer shape here (a condition is a string, a mapping or
 * a list; {@code deny} is a list of steps); their compile is the logic compiler's, reached through the
 * GUI's logic check.
 */
final class GuiOpen {

    /** The argument types the runtime parses (logic.md §6.1). */
    static final Set<String> ARG_TYPES = Set.of("string", "int", "number", "player", "boolean", "choice");

    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern ARG_NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Set<String> KEYS = Set.of("commands", "args", "permission", "requirement", "deny");

    /** A read block: the typed spec and the problems found in its authored shape. */
    record Parsed(OpenSpec spec, List<Diagnostic> problems) {}

    private GuiOpen() {}

    /**
     * Reads the authored {@code open:} value. A value that is not a mapping yields no spec and one
     * problem; a malformed part yields the spec with what could be read and the problems named.
     */
    static Parsed read(Object raw) {
        var problems = new ArrayList<Diagnostic>();
        if (!(raw instanceof Map<?, ?> mapping)) {
            problems.add(problem("open: must be a mapping of commands, args, permission, requirement and deny"));
            return new Parsed(null, problems);
        }
        Map<String, Object> block = stringKeyed(mapping);
        for (String key : block.keySet()) {
            if (!KEYS.contains(key)) {
                problems.add(problem("open: has no key '" + key + "'"));
            }
        }
        List<String> commands = new ArrayList<>();
        if (block.get("commands") instanceof List<?> names) {
            for (Object name : names) {
                commands.add(String.valueOf(name));
            }
        } else if (block.get("commands") != null) {
            problems.add(problem("open.commands must be a list of command names"));
        }
        List<OpenArg> args = new ArrayList<>();
        if (block.get("args") instanceof List<?> entries) {
            for (Object entry : entries) {
                readArg(entry, args, problems);
            }
        } else if (block.get("args") != null) {
            problems.add(problem("open.args must be a list of arguments"));
        }
        String permission = block.get("permission") instanceof String text ? text : null;
        if (block.get("permission") != null && permission == null) {
            problems.add(problem("open.permission must be a permission node"));
        }
        Object requirement = block.get("requirement");
        if (requirement != null && !(requirement instanceof String || requirement instanceof Map<?, ?>
                || requirement instanceof List<?> || requirement instanceof Boolean)) {
            problems.add(problem("open.requirement must be a condition"));
        }
        List<Object> deny = new ArrayList<>();
        if (block.get("deny") instanceof List<?> steps) {
            deny.addAll(steps);
        } else if (block.get("deny") != null) {
            problems.add(problem("open.deny must be a list of steps"));
        }
        return new Parsed(new OpenSpec(commands, args, permission, requirement, deny), problems);
    }

    /** The problems in a spec, for a write: the same checks {@link #read} makes, on the typed form. */
    static List<Diagnostic> check(OpenSpec spec) {
        var problems = new ArrayList<Diagnostic>();
        var names = new HashSet<String>();
        for (String command : spec.commands()) {
            if (!COMMAND.matcher(command).matches()) {
                problems.add(problem("command '" + command + "' must match [a-z0-9_-]+"));
            } else if (!names.add(command)) {
                problems.add(problem("command '" + command + "' is named twice"));
            }
        }
        var argNames = new HashSet<String>();
        for (OpenArg arg : spec.args()) {
            if (arg.name() == null || !ARG_NAME.matcher(arg.name()).matches()) {
                problems.add(problem("argument name '" + arg.name() + "' must match [a-z][a-z0-9_]*"));
            } else if (!argNames.add(arg.name())) {
                problems.add(problem("argument '" + arg.name() + "' is declared twice"));
            }
            if (!ARG_TYPES.contains(arg.type())) {
                problems.add(problem("argument '" + arg.name() + "' has type '" + arg.type()
                        + "'; the types are " + String.join(", ", ARG_TYPES)));
            }
        }
        if (spec.permission() != null && spec.permission().isBlank()) {
            problems.add(problem("open.permission must not be blank"));
        }
        return problems;
    }

    /** The authored mapping of a spec, in the order the reference shows (commands, args, permission, requirement, deny). */
    static Map<String, Object> write(OpenSpec spec) {
        var block = new LinkedHashMap<String, Object>();
        if (!spec.commands().isEmpty()) {
            block.put("commands", List.copyOf(spec.commands()));
        }
        if (!spec.args().isEmpty()) {
            var args = new ArrayList<Object>();
            for (OpenArg arg : spec.args()) {
                var authored = new LinkedHashMap<String, Object>();
                authored.put("name", arg.name());
                authored.put("type", arg.type());
                if (arg.optional()) {
                    authored.put("optional", true);
                }
                if (arg.defaultValue() != null) {
                    authored.put("default", arg.defaultValue());
                }
                if (!arg.suggest().isEmpty()) {
                    authored.put("suggest", List.copyOf(arg.suggest()));
                }
                args.add(authored);
            }
            block.put("args", args);
        }
        if (spec.permission() != null) {
            block.put("permission", spec.permission());
        }
        if (spec.requirement() != null) {
            block.put("requirement", spec.requirement());
        }
        if (!spec.deny().isEmpty()) {
            block.put("deny", List.copyOf(spec.deny()));
        }
        return block;
    }

    private static void readArg(Object entry, List<OpenArg> out, List<Diagnostic> problems) {
        if (!(entry instanceof Map<?, ?> raw)) {
            problems.add(problem("an open.args entry must be a mapping with name and type"));
            return;
        }
        Map<String, Object> arg = stringKeyed(raw);
        String name = arg.get("name") instanceof String text ? text : null;
        String type = arg.get("type") instanceof String text ? text : null;
        if (name == null || type == null) {
            problems.add(problem("an open.args entry needs a name and a type"));
            return;
        }
        List<String> suggest = new ArrayList<>();
        if (arg.get("suggest") instanceof List<?> choices) {
            for (Object choice : choices) {
                suggest.add(String.valueOf(choice));
            }
        }
        out.add(new OpenArg(name, type, Boolean.TRUE.equals(arg.get("optional")), arg.get("default"), suggest));
    }

    private static Diagnostic problem(String message) {
        return new Diagnostic(DiagnosticCodes.CONTENT_SCHEMA_INVALID, Severity.ERROR, message, null, null,
                "fix the open: block; see logic.md §6.1");
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> source) {
        var out = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }
}
