package dev.materione.content.commands;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.gui.GuiAuthoringApi.OpenArg;
import dev.materione.api.gui.GuiAuthoringApi.OpenSpec;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The one schema source of a command's names, typed arguments and gate (master §5.6, logic.md §40). A GUI's
 * {@code open:} block and a {@code command} document both say "these names run this, with these arguments, behind
 * this permission and requirement"; this class reads, checks and writes that block for both, so the two cannot
 * drift. The only differences are the {@link Dialect}: what the block is called in a message, and the key that holds
 * the names ({@code commands} in {@code open:}, {@code names} in a command document).
 *
 * <p>The typed form is {@link OpenSpec}. Reading never throws: a malformed part is a problem and the rest of the
 * block is kept, so one bad argument does not take the command down. Nothing here compiles a condition or a step; the
 * runtime's {@code OpenPlan} does, with the flow compiler.
 */
public final class CommandSchema {

    /** The argument types the runtime parses (logic.md §6.1). */
    public static final Set<String> ARG_TYPES = Set.of("string", "int", "number", "player", "boolean", "choice");

    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern ARG_NAME = Pattern.compile("[a-z][a-z0-9_]*");

    /** The keys a GUI's {@code open:} block accepts; the block is strict about them. */
    private static final Set<String> OPEN_KEYS =
            Set.of("commands", "args", "permission", "requirement", "deny", "visible");

    /** Who sees the command in completion: only players who may run it (the default), or everyone (#467). */
    public static final String VISIBLE_PERMITTED = "permitted";
    public static final String VISIBLE_ALWAYS = "always";
    public static final Set<String> VISIBLE = new TreeSet<>(Set.of(VISIBLE_PERMITTED, VISIBLE_ALWAYS));

    /**
     * Where the block lives, which decides its wording and its key for the names.
     *
     * <p>{@code label} is what a message calls the block, {@code namesKey} the key that lists the command names, and
     * {@code strict} whether a key outside the block's own is a problem (the {@code open:} block) or belongs to the
     * enclosing document (a command document, whose other keys are {@code do}, {@code flows}, ...).
     */
    public enum Dialect {
        OPEN("open", "commands", true, "an", "fix the open: block; see logic.md §6.1"),
        COMMAND("command", "names", false, "a", "fix the command document; see logic.md §40");

        private final String label;
        private final String namesKey;
        private final boolean strict;
        private final String article;
        private final String hint;

        Dialect(String label, String namesKey, boolean strict, String article, String hint) {
            this.label = label;
            this.namesKey = namesKey;
            this.strict = strict;
            this.article = article;
            this.hint = hint;
        }

        public String label() {
            return label;
        }

        public String namesKey() {
            return namesKey;
        }

        private String key(String name) {
            return label + "." + name;
        }
    }

    /** A read block: the typed spec and the problems found in its authored shape. */
    public record Parsed(OpenSpec spec, List<Diagnostic> problems) {}

    private CommandSchema() {}

    /**
     * Reads the authored block. A value that is not a mapping yields no spec and one problem; a malformed part
     * yields the spec with what could be read and the problems named.
     */
    public static Parsed read(Object raw, Dialect dialect) {
        var problems = new ArrayList<Diagnostic>();
        if (!(raw instanceof Map<?, ?> mapping)) {
            problems.add(problem(dialect, dialect.label + ": must be a mapping of " + dialect.namesKey
                    + ", args, permission, requirement and deny"));
            return new Parsed(null, problems);
        }
        Map<String, Object> block = stringKeyed(mapping);
        if (dialect.strict) {
            for (String key : block.keySet()) {
                if (!OPEN_KEYS.contains(key)) {
                    problems.add(problem(dialect, dialect.label + ": has no key '" + key + "'"));
                }
            }
        }
        List<String> names = new ArrayList<>();
        Object written = block.get(dialect.namesKey);
        if (written instanceof List<?> list) {
            for (Object name : list) {
                names.add(String.valueOf(name));
            }
        } else if (written instanceof String single && !dialect.strict) {
            names.add(single); // a command document may write one name bare: names: pay
        } else if (written != null) {
            problems.add(problem(dialect, dialect.key(dialect.namesKey) + " must be a list of command names"));
        }
        List<OpenArg> args = new ArrayList<>();
        if (block.get("args") instanceof List<?> entries) {
            for (Object entry : entries) {
                readArg(entry, dialect, args, problems);
            }
        } else if (block.get("args") != null) {
            problems.add(problem(dialect, dialect.key("args") + " must be a list of arguments"));
        }
        String permission = block.get("permission") instanceof String text ? text : null;
        if (block.get("permission") != null && permission == null) {
            problems.add(problem(dialect, dialect.key("permission") + " must be a permission node"));
        }
        Object requirement = block.get("requirement");
        if (requirement != null && !(requirement instanceof String || requirement instanceof Map<?, ?>
                || requirement instanceof List<?> || requirement instanceof Boolean)) {
            problems.add(problem(dialect, dialect.key("requirement") + " must be a condition"));
        }
        List<Object> deny = new ArrayList<>();
        if (block.get("deny") instanceof List<?> steps) {
            deny.addAll(steps);
        } else if (block.get("deny") != null) {
            problems.add(problem(dialect, dialect.key("deny") + " must be a list of steps"));
        }
        String visible = null;
        if (block.get("visible") != null) {
            if (block.get("visible") instanceof String text && VISIBLE.contains(text)) {
                visible = text;
            } else {
                problems.add(problem(dialect, dialect.key("visible") + " must be " + VISIBLE_PERMITTED + " or "
                        + VISIBLE_ALWAYS + "; it reads as " + VISIBLE_PERMITTED));
            }
        }
        return new Parsed(new OpenSpec(names, args, permission, requirement, deny, visible), problems);
    }

    /** The problems in a spec, for a write: the same checks {@link #read} makes, on the typed form. */
    public static List<Diagnostic> check(OpenSpec spec, Dialect dialect) {
        var problems = new ArrayList<Diagnostic>();
        var names = new HashSet<String>();
        for (String command : spec.commands()) {
            if (!COMMAND.matcher(command).matches()) {
                problems.add(problem(dialect, "command '" + command + "' must match [a-z0-9_-]+"));
            } else if (!names.add(command)) {
                problems.add(problem(dialect, "command '" + command + "' is named twice"));
            }
        }
        var argNames = new HashSet<String>();
        for (OpenArg arg : spec.args()) {
            if (arg.name() == null || !ARG_NAME.matcher(arg.name()).matches()) {
                problems.add(problem(dialect, "argument name '" + arg.name() + "' must match [a-z][a-z0-9_]*"));
            } else if (!argNames.add(arg.name())) {
                problems.add(problem(dialect, "argument '" + arg.name() + "' is declared twice"));
            }
            if (!ARG_TYPES.contains(arg.type())) {
                problems.add(problem(dialect, "argument '" + arg.name() + "' has type '" + arg.type()
                        + "'; the types are " + String.join(", ", ARG_TYPES)));
            }
            checkOptions(arg, dialect, problems);
        }
        if (spec.visible() != null && !VISIBLE.contains(spec.visible())) {
            problems.add(problem(dialect, dialect.key("visible") + " must be " + VISIBLE_PERMITTED + " or "
                    + VISIBLE_ALWAYS));
        }
        if (spec.permission() != null && spec.permission().isBlank()) {
            problems.add(problem(dialect, dialect.key("permission") + " must not be blank"));
        }
        return problems;
    }

    /** The bounds and the player options belong to the types that read them, and a range must not be empty. */
    private static void checkOptions(OpenArg arg, Dialect dialect, List<Diagnostic> problems) {
        boolean numeric = "int".equals(arg.type()) || "number".equals(arg.type());
        if ((arg.min() != null || arg.max() != null) && !numeric) {
            problems.add(problem(dialect, "argument '" + arg.name() + "' has min or max, which only an int or a number takes"));
        }
        if (arg.min() != null && arg.max() != null && arg.min().doubleValue() > arg.max().doubleValue()) {
            problems.add(problem(dialect, "argument '" + arg.name() + "' has a min above its max, so no value fits"));
        }
        if ((arg.offline() || arg.selector()) && !"player".equals(arg.type())) {
            problems.add(problem(dialect, "argument '" + arg.name()
                    + "' has offline or selector, which only a player argument takes"));
        }
    }

    /** The authored mapping of a spec, in the order the reference shows (names, args, permission, requirement, deny). */
    public static Map<String, Object> write(OpenSpec spec, Dialect dialect) {
        var block = new LinkedHashMap<String, Object>();
        if (!spec.commands().isEmpty()) {
            block.put(dialect.namesKey, List.copyOf(spec.commands()));
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
                if (arg.min() != null) {
                    authored.put("min", arg.min());
                }
                if (arg.max() != null) {
                    authored.put("max", arg.max());
                }
                if (arg.offline()) {
                    authored.put("offline", true);
                }
                if (arg.selector()) {
                    authored.put("selector", true);
                }
                args.add(authored);
            }
            block.put("args", args);
        }
        if (spec.permission() != null) {
            block.put("permission", spec.permission());
        }
        if (spec.visible() != null) {
            block.put("visible", spec.visible());
        }
        if (spec.requirement() != null) {
            block.put("requirement", spec.requirement());
        }
        if (!spec.deny().isEmpty()) {
            block.put("deny", List.copyOf(spec.deny()));
        }
        return block;
    }

    // ------------------------------------------------------------------
    // self-description: the same keys, drawn once for the editor, authoring.describe and the SDK

    /** The fields of one {@code args} entry, as the self-description lists them (an argument is the same in both homes). */
    public static List<ParameterSchema> argumentFields() {
        return List.of(
                new ParameterSchema("name", ParameterType.STRING, true, null,
                        "The argument's name, [a-z][a-z0-9_]*; ${args.<name>} reads it", null, null),
                new ParameterSchema("type", ParameterType.ENUM, true, null,
                        "How the text typed is read: string, int, number, player (an online player's exact name), "
                                + "boolean, or choice (one of suggest)",
                        List.copyOf(new TreeSet<>(ARG_TYPES)), null),
                new ParameterSchema("optional", ParameterType.BOOLEAN, false, "false",
                        "Whether the argument may be left out", null, null),
                new ParameterSchema("default", ParameterType.STRING, false, null,
                        "The value used when the argument is left out; it must read as the argument's type", null, null),
                new ParameterSchema("suggest", ParameterType.LIST, false, null,
                        "The completions the command offers; for a choice, also the only values it accepts", null, null),
                new ParameterSchema("min", ParameterType.DECIMAL, false, null,
                        "The smallest value an int or a number accepts; the client refuses a smaller one as it is typed",
                        null, null),
                new ParameterSchema("max", ParameterType.DECIMAL, false, null,
                        "The largest value an int or a number accepts; the client refuses a larger one as it is typed",
                        null, null),
                new ParameterSchema("offline", ParameterType.BOOLEAN, false, "false",
                        "For a player: also accept any player who has joined before, not only one who is online",
                        null, null),
                new ParameterSchema("selector", ParameterType.BOOLEAN, false, "false",
                        "For a player: also accept a vanilla selector (@p, @a[distance=..5]) when the sender may use "
                                + "selectors; a command runs once for each player it matches, up to 16",
                        null, null));
    }

    /** The {@code args} list of a command. */
    public static ParameterSchema arguments(String description) {
        return new ParameterSchema("args", ParameterType.LIST, false, null, description, null, argumentFields());
    }

    /** The {@code permission} node a command needs. */
    public static ParameterSchema permission(String description) {
        return new ParameterSchema("permission", ParameterType.STRING, false, null, description, null, null);
    }

    /** The {@code visible} switch of a command: who sees it in completion. */
    public static ParameterSchema visible(String description) {
        return new ParameterSchema("visible", ParameterType.ENUM, false, VISIBLE_PERMITTED, description,
                List.of(VISIBLE_PERMITTED, VISIBLE_ALWAYS), null);
    }

    /** The {@code requirement} condition of a command: the one a GUI's open and a command document share. */
    public static ParameterSchema requirement(String description) {
        return new ParameterSchema("requirement", ParameterType.RAW, false, null, description, null, null)
                .ofKind(ParameterSchema.KIND_CONDITION);
    }

    /** The {@code deny} steps that run when the requirement fails. */
    public static ParameterSchema deny(String description) {
        return new ParameterSchema("deny", ParameterType.RAW, false, null, description, null, null)
                .ofKind(ParameterSchema.KIND_STEPS);
    }

    // ------------------------------------------------------------------

    private static void readArg(Object entry, Dialect dialect, List<OpenArg> out, List<Diagnostic> problems) {
        if (!(entry instanceof Map<?, ?> raw)) {
            problems.add(problem(dialect, dialect.article + " " + dialect.key("args")
                    + " entry must be a mapping with name and type"));
            return;
        }
        Map<String, Object> arg = stringKeyed(raw);
        String name = arg.get("name") instanceof String text ? text : null;
        String type = arg.get("type") instanceof String text ? text : null;
        if (name == null || type == null) {
            problems.add(problem(dialect, dialect.article + " " + dialect.key("args")
                    + " entry needs a name and a type"));
            return;
        }
        List<String> suggest = new ArrayList<>();
        if (arg.get("suggest") instanceof List<?> choices) {
            for (Object choice : choices) {
                suggest.add(String.valueOf(choice));
            }
        }
        out.add(new OpenArg(name, type, Boolean.TRUE.equals(arg.get("optional")), arg.get("default"), suggest,
                number(arg, "min", dialect, name, problems), number(arg, "max", dialect, name, problems),
                Boolean.TRUE.equals(arg.get("offline")), Boolean.TRUE.equals(arg.get("selector"))));
    }

    /** A bound as written, or null when absent or not a number (a problem then). */
    private static Number number(Map<String, Object> arg, String key, Dialect dialect, String name,
            List<Diagnostic> problems) {
        Object value = arg.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number;
        }
        problems.add(problem(dialect, "argument '" + name + "' " + key + " must be a number"));
        return null;
    }

    private static Diagnostic problem(Dialect dialect, String message) {
        return new Diagnostic(DiagnosticCodes.CONTENT_SCHEMA_INVALID, Severity.ERROR, message, null, null, dialect.hint);
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> source) {
        var out = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }
}
