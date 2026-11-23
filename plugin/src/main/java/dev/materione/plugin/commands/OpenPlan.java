package dev.materione.plugin.commands;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.gui.GuiAuthoringApi.OpenArg;
import dev.materione.api.gui.GuiAuthoringApi.OpenSpec;
import dev.materione.api.logic.LogicContext;
import dev.materione.behavior.flow.Condition;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import dev.materione.content.commands.CommandSchema;
import dev.materione.content.gui.logic.GuiRules;
import dev.materione.plugin.gui.rules.GuiRuleSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A GUI's {@code open:} block compiled for the runtime (logic.md §6.1): the commands, the typed arguments,
 * the permission, the requirement as a condition and the deny steps as a rule. The typing happens here, at
 * load, so opening reads no raw map (logic.md §9 rule 5). Built with the live view, and read by the gate,
 * the command registrar and the usage message on every open, so a reload that changes the block is seen by
 * the next open.
 *
 * <p>Problems are returned, not published: the diagnose stage reports the same ones from the same compile.
 *
 * @param commands the command names that open the GUI, in authored order
 * @param args the typed arguments, in authored order
 * @param permission the node the opener needs, or null
 * @param requirement the requirement as a condition, or null when none is authored or it did not load
 * @param hasRequirement whether the author wrote a requirement, loadable or not
 * @param deny the deny steps as a rule with no condition, or null when there are none or they did not load
 * @param problems what the compile found, each with its code
 * @param visibleAlways whether a player who lacks {@code permission} still sees the command in completion
 *     ({@code visible: always}); by default only players who may run it do (logic.md §40, #467)
 */
public record OpenPlan(
        List<String> commands,
        List<OpenArgSpec> args,
        String permission,
        Condition requirement,
        boolean hasRequirement,
        Rule deny,
        List<Diagnostic> problems,
        boolean visibleAlways) {

    /** The trigger the requirement and the deny steps run under: the GUI's opening, with its variables. */
    static final String TRIGGER = GuiRuleSet.OPENING;

    public OpenPlan {
        commands = List.copyOf(commands);
        args = List.copyOf(args);
        problems = List.copyOf(problems);
    }

    /** A plan with the default visibility: only players who may run the command see it. */
    public OpenPlan(
            List<String> commands,
            List<OpenArgSpec> args,
            String permission,
            Condition requirement,
            boolean hasRequirement,
            Rule deny,
            List<Diagnostic> problems) {
        this(commands, args, permission, requirement, hasRequirement, deny, problems, false);
    }

    /** This plan with other command names (a command document keeps only the names that are valid). */
    public OpenPlan withCommands(List<String> names) {
        return new OpenPlan(names, args, permission, requirement, hasRequirement, deny, problems, visibleAlways);
    }

    /** Whether {@code sender} sees the command in completion and can reach it: the permission, unless it is always visible. */
    public boolean visibleTo(org.bukkit.command.CommandSender sender) {
        return visibleAlways || permission == null || sender.hasPermission(permission);
    }

    /**
     * Compiles the block of {@code rules}.
     *
     * @param owner the GUI the block belongs to
     * @param rules the GUI's sections, with the block as read
     * @param lookup the registries the requirement and the deny steps resolve against; null compiles the
     *     block without them, and a requirement then fails closed
     * @return the plan, or null when the GUI has no {@code open:} block
     */
    public static OpenPlan compile(NamespacedId owner, GuiRules rules, LogicLookup lookup) {
        if (rules == null || rules.open() == null) {
            return null;
        }
        return compile(owner, rules.open(), rules.flows(), TRIGGER, FlowCompiler.OPEN_SECTION, lookup);
    }

    /**
     * Compiles a names, arguments and gate block wherever it lives (logic.md §40): a GUI's {@code open:} block under
     * {@code gui.opening}, or a command document's own keys under {@code command.run}. The two differ only in the
     * trigger the requirement and the deny steps are checked under, and in where a problem says the block sits.
     *
     * @param owner the GUI or command document the block belongs to
     * @param spec the block as read
     * @param flows the owner's named flows, which the deny steps may call
     * @param trigger the trigger whose variables the requirement and the deny steps may read
     * @param section {@code open} for a GUI's block, empty for a command document (its keys are the document's own)
     * @param lookup the registries the requirement and the deny steps resolve against; null compiles the block
     *     without them, and a requirement then fails closed
     */
    public static OpenPlan compile(
            NamespacedId owner, OpenSpec spec, Object flows, String trigger, String section, LogicLookup lookup) {
        var problems = new ArrayList<Diagnostic>();
        List<OpenArgSpec> args = arguments(owner, spec.args(), problems);
        Condition requirement = null;
        boolean broken = false;
        if (spec.requirement() != null) {
            if (lookup == null) {
                broken = true;
            } else {
                var parsed = FlowCompiler.condition(owner, spec.requirement(), trigger, lookup, section);
                problems.addAll(parsed.problems());
                requirement = parsed.condition();
                broken = requirement == null;
            }
            if (broken) {
                boolean command = section.isEmpty();
                problems.add(problem(owner, DiagnosticCodes.OPEN_REQUIREMENT_INVALID, Severity.ERROR,
                        (command ? "requirement" : section + ".requirement") + " does not load, so "
                                + (command ? "the command" : "opening") + " is refused for everyone without the bypass",
                        "fix the condition reported beside it"));
            }
        }
        Rule deny = null;
        if (!spec.deny().isEmpty() && lookup != null) {
            ParsedRules parsed = FlowCompiler.denied(owner, trigger, spec.deny(), flows, lookup, section);
            problems.addAll(parsed.problems());
            deny = parsed.rules().isEmpty() ? null : parsed.rules().get(0);
        }
        return new OpenPlan(spec.commands(), args, spec.permission(), requirement, spec.requirement() != null,
                deny, problems, CommandSchema.VISIBLE_ALWAYS.equals(spec.visible()));
    }

    /** The argument with this name, if the block declares it. */
    public Optional<OpenArgSpec> arg(String name) {
        return args.stream().filter(arg -> arg.name().equals(name)).findFirst();
    }

    /**
     * Whether the requirement holds. A requirement that did not load, or whose evaluation throws, does not
     * hold: a broken check never grants what it guards. True when none is authored.
     */
    public boolean requirementMet(LogicContext context) {
        if (!hasRequirement) {
            return true;
        }
        if (requirement == null) {
            return false;
        }
        try {
            return requirement.test(context);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The node that skips a command document's requirement: {@code materione.command.bypass.<pack>.<id>} (logic.md §40). */
    public static String commandBypassNode(NamespacedId command) {
        Objects.requireNonNull(command, "command");
        return "materione.command.bypass." + command.namespace() + "." + command.value();
    }

    /** The node that skips a GUI's requirement: {@code materione.gui.bypass.<pack>.<id>} (logic.md §6.1). */
    public static String bypassNode(NamespacedId gui) {
        return bypassNode("gui", gui);
    }

    /**
     * The node that skips an opened view's requirement: {@code materione.<kind>.bypass.<pack>.<id>}, for the kind
     * that is opened ({@code gui}, {@code dialog}; views.md section 3.5, dialogs.md section 7.1).
     */
    public static String bypassNode(String kind, NamespacedId view) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(view, "view");
        return "materione." + kind + ".bypass." + view.namespace() + "." + view.value();
    }

    /** Types the arguments; an argument that cannot be typed is reported and kept as the string it was written as. */
    private static List<OpenArgSpec> arguments(NamespacedId owner, List<OpenArg> written, List<Diagnostic> out) {
        var args = new ArrayList<OpenArgSpec>();
        var names = new java.util.HashSet<String>();
        for (OpenArg arg : written) {
            Optional<ArgType> declared = ArgType.of(arg.type());
            if (declared.isEmpty() || !names.add(arg.name())) {
                continue; // the authoring check reports the unknown type and the duplicate name
            }
            ArgType type = declared.get();
            if (type == ArgType.CHOICE && arg.suggest().isEmpty()) {
                out.add(problem(owner, DiagnosticCodes.OPEN_ARGUMENT_INVALID, Severity.WARN,
                        "argument '" + arg.name() + "' is a choice with no suggest: list, so it reads as a string",
                        "list the allowed values under suggest:"));
                type = ArgType.STRING;
            }
            Double[] range = range(owner, arg, type, out);
            boolean player = type == ArgType.PLAYER;
            var spec = new OpenArgSpec(arg.name(), type, arg.optional(), null, arg.suggest(), range[0], range[1],
                    player && arg.offline(), player && arg.selector());
            args.add(new OpenArgSpec(spec.name(), type, arg.optional(), defaultOf(owner, arg, spec, out),
                    arg.suggest(), range[0], range[1], spec.offline(), spec.selector()));
        }
        return args;
    }

    /**
     * The bounds of a number: {@code [min, max]}, each null when unset. An {@code int} rounds them inward (a minimum up,
     * a maximum down), so {@code min: 1.5} means 2. A range no value fits (min above max), or bounds on a type that does
     * not read them, are dropped with a warning: the argument is then unbounded rather than unusable.
     */
    private static Double[] range(NamespacedId owner, OpenArg arg, ArgType type, List<Diagnostic> out) {
        Double min = arg.min() == null ? null : arg.min().doubleValue();
        Double max = arg.max() == null ? null : arg.max().doubleValue();
        if (min == null && max == null) {
            return new Double[] {null, null};
        }
        if (type != ArgType.INT && type != ArgType.NUMBER) {
            out.add(problem(owner, DiagnosticCodes.OPEN_ARGUMENT_INVALID, Severity.WARN,
                    "argument '" + arg.name() + "' has min or max, which only an int or a number takes; they are ignored",
                    "remove min and max, or make the argument an int or a number"));
            return new Double[] {null, null};
        }
        if (type == ArgType.INT) {
            min = min == null ? null : Math.ceil(min);
            max = max == null ? null : Math.floor(max);
        }
        if (min != null && max != null && min > max) {
            out.add(problem(owner, DiagnosticCodes.OPEN_ARGUMENT_INVALID, Severity.WARN,
                    "argument '" + arg.name() + "' has a min above its max, so no value fits; the bounds are ignored",
                    "make min no larger than max"));
            return new Double[] {null, null};
        }
        return new Double[] {min, max};
    }

    /** The default in canonical text, or null (dropped, with a warning) when it does not read as the type. */
    private static String defaultOf(NamespacedId owner, OpenArg arg, OpenArgSpec spec, List<Diagnostic> out) {
        if (arg.defaultValue() == null) {
            return null;
        }
        ArgType type = spec.type();
        String text = String.valueOf(arg.defaultValue());
        if (type == ArgType.PLAYER) {
            return text; // a player is only known at open time: the default is a name, checked then
        }
        ArgType.Typed typed = type.parse(text, spec, PlayerNames.NONE);
        if (typed.ok()) {
            return typed.value();
        }
        out.add(problem(owner, DiagnosticCodes.OPEN_ARGUMENT_INVALID, Severity.WARN,
                "argument '" + arg.name() + "' default '" + text + "' " + typed.error() + "; the default is dropped",
                "write a default of type " + type.word()));
        return null;
    }

    private static Diagnostic problem(
            NamespacedId owner, DiagnosticCode code, Severity severity, String message, String hint) {
        return new Diagnostic(code, severity, message, owner, null, hint);
    }
}
