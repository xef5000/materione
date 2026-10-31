package dev.materione.plugin.gui.open;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.gui.GuiAuthoringApi.OpenArg;
import dev.materione.api.logic.LogicContext;
import dev.materione.behavior.flow.Condition;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
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
 */
public record OpenPlan(
        List<String> commands,
        List<OpenArgSpec> args,
        String permission,
        Condition requirement,
        boolean hasRequirement,
        Rule deny,
        List<Diagnostic> problems) {

    /** The trigger the requirement and the deny steps run under: the GUI's opening, with its variables. */
    static final String TRIGGER = GuiRuleSet.OPENING;

    public OpenPlan {
        commands = List.copyOf(commands);
        args = List.copyOf(args);
        problems = List.copyOf(problems);
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
        var spec = rules.open();
        var problems = new ArrayList<Diagnostic>();
        List<OpenArgSpec> args = arguments(owner, spec.args(), problems);
        Condition requirement = null;
        boolean broken = false;
        if (spec.requirement() != null) {
            if (lookup == null) {
                broken = true;
            } else {
                var parsed = FlowCompiler.condition(owner, spec.requirement(), TRIGGER, lookup);
                problems.addAll(parsed.problems());
                requirement = parsed.condition();
                broken = requirement == null;
            }
            if (broken) {
                problems.add(problem(owner, DiagnosticCodes.OPEN_REQUIREMENT_INVALID, Severity.ERROR,
                        "open.requirement does not load, so opening is refused for everyone without the bypass",
                        "fix the condition reported beside it"));
            }
        }
        Rule deny = null;
        if (!spec.deny().isEmpty() && lookup != null) {
            ParsedRules parsed = FlowCompiler.denied(owner, TRIGGER, spec.deny(), rules.flows(), lookup);
            problems.addAll(parsed.problems());
            deny = parsed.rules().isEmpty() ? null : parsed.rules().get(0);
        }
        return new OpenPlan(spec.commands(), args, spec.permission(), requirement, spec.requirement() != null,
                deny, problems);
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

    /** The node that skips a GUI's requirement: {@code materione.gui.bypass.<pack>.<id>} (logic.md §6.1). */
    public static String bypassNode(NamespacedId gui) {
        Objects.requireNonNull(gui, "gui");
        return "materione.gui.bypass." + gui.namespace() + "." + gui.value();
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
            args.add(new OpenArgSpec(arg.name(), type, arg.optional(),
                    defaultOf(owner, arg, type, out), arg.suggest()));
        }
        return args;
    }

    /** The default in canonical text, or null (dropped, with a warning) when it does not read as the type. */
    private static String defaultOf(NamespacedId owner, OpenArg arg, ArgType type, List<Diagnostic> out) {
        if (arg.defaultValue() == null) {
            return null;
        }
        String text = String.valueOf(arg.defaultValue());
        if (type == ArgType.PLAYER) {
            return text; // a player is only known at open time: the default is a name, checked then
        }
        ArgType.Typed typed = type.parse(text, arg.suggest(), PlayerNames.NONE);
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
