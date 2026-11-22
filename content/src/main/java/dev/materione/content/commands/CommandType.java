package dev.materione.content.commands;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.content.ResourcePresentation;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticTarget;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.gui.GuiAuthoringApi.OpenSpec;
import dev.materione.api.logic.ContextVariable;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.content.Templates;
import dev.materione.content.logic.LogicSchemas;
import dev.materione.core.document.LosslessYaml;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code command} content type (logic.md §40): a command a pack declares with no GUI to open. Its names,
 * typed arguments, permission, requirement and deny are the block a GUI's {@code open:} reads, from the same schema
 * ({@link CommandSchema}); what it adds is the flow it runs ({@code do:}, with {@code flows:} for named flows) and
 * whether the console may run it ({@code console:}).
 *
 * <pre>{@code
 * pay:
 *   names: [pay]
 *   args:
 *     - {name: to, type: player}
 *     - {name: amount, type: number}
 *   permission: shop.pay
 *   do:
 *     - take_money: "${args.amount}"
 *     - give_money: {amount: "${args.amount}", target: {arg: to}}
 * }</pre>
 *
 * <p>Nothing is emitted to the pack: a command is runtime logic. Parsing is pure and never throws. A document whose
 * {@code do:} is not a list or whose {@code flows:} is not a mapping parses to nothing and the pipeline quarantines it;
 * a malformed name, argument or gate is a diagnostic on the definition and the rest loads.
 */
public final class CommandType implements ContentType {

    /** The name the registry, the vocabulary's {@code firedBy} and the editor know this host by. */
    public static final String NAME = "command";

    /** The trigger a command's {@code do:} runs under. Its context is declared by the plugin's registration of it. */
    public static final String TRIGGER = "command.run";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String directory() {
        // plural like items/ and rules/: the pipeline derives the type by stripping the trailing "s"
        return "commands";
    }

    @Override
    public SelfDescription describe() {
        var scope = List.of(
                ContextVariable.of("args.*", ParameterType.RAW,
                        "The typed arguments the sender gave, by name: whole numbers and numbers as numbers, "
                                + "true and false as booleans, everything else as text"),
                ContextVariable.of("command.name", ParameterType.STRING, "The command's first name"),
                ContextVariable.of("command.label", ParameterType.STRING, "The name the sender typed"));
        return new SelfDescription(
                "Command",
                "A command players type, with typed arguments, a permission and a requirement, that runs one flow "
                        + "of steps: no item, GUI or other host to hang it on",
                List.of(
                        new ParameterSchema("description", ParameterType.STRING, false, null,
                                "A note for the people who edit this document", null, null),
                        new ParameterSchema("names", ParameterType.LIST, true, null,
                                "The names that run the command, each [a-z0-9_-]+ and none already a command "
                                        + "(the first is its name)", null, null)
                                .withEditor(editor("Names", 10)),
                        CommandSchema.arguments("The typed arguments after the name; ${args.<name>} reads each")
                                .withEditor(editor("Arguments", 20)),
                        CommandSchema.permission("The permission node a sender needs; none means anyone may run it")
                                .withEditor(editor("Permission", 30)),
                        CommandSchema.requirement("A condition the sender must meet; the deny steps run when it fails")
                                .withEditor(editor("Requirement", 40)),
                        CommandSchema.deny("Steps that run when the requirement fails, then the command is refused")
                                .withEditor(editor("Deny", 50)),
                        new ParameterSchema("console", ParameterType.BOOLEAN, false, "false",
                                "Whether a sender with no player (the console, a command block) may run it; "
                                        + "otherwise it is refused", null, null)
                                .withEditor(editor("Console", 60)),
                        new ParameterSchema("do", ParameterType.RAW, true, null,
                                "The steps the command runs: the one flow model, with ${args.*}, ${command.name}"
                                        + " and ${command.label} in context", null, null)
                                .ofKind(ParameterSchema.KIND_STEPS)
                                .opening(scope)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Do", "behavior", 70, false, "advanced", "", "")),
                        LogicSchemas.flows()),
                "1.0.0");
    }

    private static ParameterSchema.EditorMetadata editor(String label, int order) {
        return new ParameterSchema.EditorMetadata(label, "behavior", order, false, "text", "", "");
    }

    @Override
    public Optional<Object> parse(
            NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
        var merged = Templates.merge(definition, id.namespace(), templates);
        if (merged == null) {
            return Optional.empty(); // unknown or diamond template: quarantine upstream
        }
        Object steps = merged.get("do");
        if (steps != null && !(steps instanceof List<?>)) {
            return Optional.empty();
        }
        Object flows = merged.get("flows");
        if (flows != null && !(flows instanceof Map<?, ?>)) {
            return Optional.empty();
        }
        CommandSchema.Parsed block = CommandSchema.read(merged, CommandSchema.Dialect.COMMAND);
        OpenSpec spec = block.spec();
        var problems = new ArrayList<Diagnostic>();
        block.problems().forEach(problem -> problems.add(onDocument(id, problem)));
        CommandSchema.check(spec, CommandSchema.Dialect.COMMAND).forEach(problem -> problems.add(onDocument(id, problem)));
        Object console = merged.get("console");
        if (console != null && !(console instanceof Boolean)) {
            problems.add(new Diagnostic(DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN,
                    "command.console must be true or false; the console is refused", id, null,
                    "write console: true to let the console run it", DiagnosticTarget.field("console")));
        }
        @SuppressWarnings("unchecked")
        List<Object> body = steps == null ? List.of() : (List<Object>) LosslessYaml.stringKeyedDeep(steps);
        @SuppressWarnings("unchecked")
        Map<String, Object> named = flows == null ? null : (Map<String, Object>) LosslessYaml.stringKeyedDeep(flows);
        return Optional.of(new CommandDefinition(id, Templates.text(merged, "description"), spec,
                Boolean.TRUE.equals(console), body, named, merged, problems));
    }

    /** A shape problem of the block, said about the document: a warning that points at its field, never an error. */
    private static Diagnostic onDocument(NamespacedId id, Diagnostic problem) {
        return new Diagnostic(DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN, problem.message(), id, null,
                problem.hint());
    }

    @Override
    public List<Diagnostic> diagnose(Object parsedDefinition) {
        if (!(parsedDefinition instanceof CommandDefinition command)) {
            return List.of();
        }
        var findings = new ArrayList<>(command.problems());
        if (command.spec().commands().isEmpty()) {
            findings.add(new Diagnostic(DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN,
                    "command " + command.id() + " declares no names, so nothing is registered", command.id(), null,
                    "list at least one name under names:", DiagnosticTarget.field("names")));
        }
        if (command.steps().isEmpty()) {
            findings.add(new Diagnostic(DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN,
                    "command " + command.id() + " has no do: steps, so it is not registered", command.id(), null,
                    "add at least one step under do:", DiagnosticTarget.field("do")));
        }
        return findings;
    }

    @Override
    public Optional<ResourcePresentation> presentation(Object parsedDefinition) {
        if (!(parsedDefinition instanceof CommandDefinition command)) {
            return Optional.empty();
        }
        return Optional.of(new ResourcePresentation(command.description(), null, null, null));
    }

    @Override
    public void compile(Object definition, NamespacedId allocatedModelId, PackEmitter emitter, Allocations allocations) {
        // no pack output: a command is runtime logic, compiled into the shadow's command view
    }
}
