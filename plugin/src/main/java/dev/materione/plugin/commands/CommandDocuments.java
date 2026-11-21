package dev.materione.plugin.commands;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.content.commands.CommandDefinition;
import dev.materione.content.commands.CommandType;
import dev.materione.plugin.gui.open.OpenPlan;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Compiles one command document for the shadow (logic.md §40): its {@code do:} and named {@code flows:} through the
 * flow compiler an item's rules go through, and its names, arguments and gate through the same {@link OpenPlan} a GUI's
 * {@code open:} block compiles to. So a command reports exactly the problems a rule or an {@code open:} block would,
 * each with its path in the document ({@code do.1}, {@code requirement}, {@code deny.0}).
 *
 * <p>The shape problems of the block (a bad name, an unknown argument type) and a document with no names or no
 * {@code do:} were already published by the pack compile (the content type's diagnose), so they are not published
 * again here: the document is simply not live.
 */
public final class CommandDocuments {

    /** The names the open path registers: lowercase letters, digits, underscore and hyphen (Brigadier-safe). */
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]+");

    private CommandDocuments() {}

    /**
     * @param report where the compile's problems go; the document's own shape problems are not among them
     * @return the live command, or empty when the document declares no usable name or its flow did not load
     */
    public static Optional<RuntimeCommands.Live> compile(
            CommandDefinition command, LogicLookup lookup, Consumer<Diagnostic> report) {
        // the flow first: a command whose steps do not load claims no name, so it cannot shadow another's
        ParsedRules flow = FlowCompiler.document(
                command.id(), CommandType.TRIGGER, command.steps(), command.flows(), lookup);
        flow.problems().forEach(report);
        var names = new LinkedHashSet<String>();
        for (String name : command.spec().commands()) {
            if (NAME.matcher(name).matches()) {
                names.add(name); // a bad name was reported by the pack compile; the good ones still run the command
            }
        }
        if (names.isEmpty() || command.steps().isEmpty()) {
            return Optional.empty();
        }
        if (flow.rules().isEmpty()) {
            report.accept(new Diagnostic(DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN,
                    "command " + command.id() + " has no do: step that loads, so it is not registered",
                    command.id(), null, "fix the steps reported above"));
            return Optional.empty();
        }
        OpenPlan compiled = OpenPlan.compile(
                command.id(), command.spec(), command.flows(), CommandType.TRIGGER, "", lookup);
        compiled.problems().forEach(report);
        var plan = new OpenPlan(new ArrayList<>(names), compiled.args(), compiled.permission(), compiled.requirement(),
                compiled.hasRequirement(), compiled.deny(), compiled.problems());
        return Optional.of(new RuntimeCommands.Live(
                command.id(), command.description(), plan, command.console(), flow.rules().get(0)));
    }
}
