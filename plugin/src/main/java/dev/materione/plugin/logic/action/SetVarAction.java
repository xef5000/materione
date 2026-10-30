package dev.materione.plugin.logic.action;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ParseRejection;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.ValueExpression;
import dev.materione.api.logic.variable.CompileEnvironment;
import dev.materione.api.logic.variable.SessionVariables;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableFacets;
import dev.materione.api.logic.variable.VariableOp;
import dev.materione.api.logic.variable.VariableOps;
import dev.materione.api.logic.variable.VariableRef;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.Arrays;
import java.util.List;

/**
 * {@code set_var}: stores a value in a variable (logic.md §3.3).
 *
 * <pre>{@code
 * - set_var: {var: count, value: "${vars.count + 1}"}          # run-local (the M2 shape: name: works too)
 * - set_var: {var: player.shop.coins, op: add, value: 5}        # declared player variable
 * - set_var: {var: server.shop.season, op: set, value: "winter"}
 * - set_var: {var: session.page, op: toggle}                    # GUI session variable
 * }</pre>
 *
 * <p>The target and the operation are decided at load. An undeclared persistent name, a reserved
 * name, a literal that does not fit the declared type and an operation the type does not take are
 * rejected with their own codes ({@code MTR-0063}, {@code MTR-0064}) and the step is skipped. A
 * computed value that does not fit at run time fails the step.
 *
 * <p>The value is decided once, at load, by how it is written: a string with {@code ${...}} is a
 * template (the result is text); a string that reads as an arithmetic or logic expression is
 * evaluated to a typed value; anything else is stored as written.
 */
public final class SetVarAction implements ActionType<SetVarAction.Params> {

    /** The operation ids, in the order the editor lists them. */
    private static final List<String> OP_OPTIONS = Arrays.stream(VariableOp.values())
            .map(VariableOp::id)
            .toList();

    /** How the value is produced when the step runs. */
    public sealed interface Value permits Literal, Templated, Computed {}

    /** Stored as written (numbers, booleans, plain strings, lists). */
    public record Literal(Object value) implements Value {}

    /** A string with {@code ${...}}: rendered to text each run. */
    public record Templated(TemplateText text) implements Value {}

    /** A bare expression: evaluated to a typed value each run. */
    public record Computed(ValueExpression expression) implements Value {}

    /** Where the value goes: a run-local, the GUI session, or a declared persistent variable. */
    public sealed interface Target permits LocalTarget, SessionTarget, PersistentTarget {}

    /** A run-local variable (the M2 {@code vars.<name>}). */
    public record LocalTarget(String name) implements Target {}

    /** A variable of the open GUI session. */
    public record SessionTarget(String name) implements Target {}

    /** A declared persistent variable, resolved at load. */
    public record PersistentTarget(VariableDeclaration declaration) implements Target {}

    /** The parsed step: where, what, and with which value. */
    public record Params(Target target, VariableOp op, Value value) {}

    @Override
    public String id() {
        return "set_var";
    }

    @Override
    public ActionDescription describe() {
        return ActionDescription.of(new SelfDescription("Set variable",
                "Stores a value in a run-local, session or declared player or server variable",
                List.of(
                        new ParameterSchema("var", ParameterType.STRING, false, null,
                                "Variable: a local name, session.<name>, player.<key> or server.<pack>.<key>",
                                null, null),
                        new ParameterSchema("name", ParameterType.STRING, false, null,
                                "Run-local variable name (the M2 spelling of var:)", null, null),
                        new ParameterSchema("op", ParameterType.STRING, false, "set",
                                "What to do with the value", OP_OPTIONS, null),
                        new ParameterSchema("value", ParameterType.STRING, false, null,
                                "Value or ${expression}; not used by toggle, remove and reset", null, null)),
                "2.0.0"), LogicCategory.DATA);
    }

    @Override
    public Threading threading() {
        return Threading.ANY;
    }

    /** A declared player variable is read and written on the player's own thread. */
    @Override
    public Threading threading(Params params) {
        if (params.target() instanceof PersistentTarget persistent
                && persistent.declaration().scope() == VariableScope.PLAYER) {
            return Threading.PLAYER_REGION;
        }
        return Threading.ANY;
    }

    @Override
    public Params parse(ParamReader params) {
        boolean legacy = params.has("name") && !params.has("var");
        if (!legacy && !params.has("var")) {
            throw new IllegalArgumentException("var is required (name is the M2 spelling of a run-local)");
        }
        String ownPack = params.environment().pack();
        VariableRef ref;
        try {
            ref = legacy
                    ? VariableRef.parse(params.text("name"), ownPack)
                    : VariableRef.parse(params.text("var"), ownPack);
        } catch (IllegalArgumentException malformed) {
            // a name that cannot be a variable (a scope word, a dotted local) is an undeclared name
            throw new ParseRejection(DiagnosticCodes.VARIABLE_UNDECLARED, malformed.getMessage());
        }
        VariableOp op = legacy ? VariableOp.SET : opOf(params);
        Target target = targetOf(ref, params.environment());
        Value value = valueOf(params, op, target);
        return new Params(target, op, value);
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        return switch (params.target()) {
            case LocalTarget local -> writeLocal(local.name(), params, context);
            case SessionTarget session -> writeSession(session.name(), params, context);
            case PersistentTarget persistent -> writePersistent(persistent.declaration(), params, context);
        };
    }

    // --- load ---------------------------------------------------------------------

    private static VariableOp opOf(ParamReader params) {
        String word = params.has("op") ? params.text("op") : VariableOp.SET.id();
        return VariableOp.of(word).orElseThrow(() -> new IllegalArgumentException(
                "op must be one of set, add, subtract, multiply, toggle, remove, reset"));
    }

    /** The target, and for a persistent one its declaration: an undeclared name fails here. */
    private static Target targetOf(VariableRef ref, CompileEnvironment env) {
        return switch (ref) {
            case VariableRef.Local local -> new LocalTarget(local.name());
            case VariableRef.Session session -> new SessionTarget(session.name());
            case VariableRef.Persistent persistent -> {
                NamespacedId id = persistent.id();
                VariableDeclaration declaration = env.variable(id).orElseThrow(() -> new ParseRejection(
                        DiagnosticCodes.VARIABLE_UNDECLARED,
                        "'" + id + "' is not a declared variable (declare it in a variables file of its pack)"));
                if (persistent.scope() != null && declaration.scope() != persistent.scope()) {
                    throw new ParseRejection(DiagnosticCodes.VARIABLE_UNDECLARED, "'" + id
                            + "' is declared " + declaration.scope().id() + ", not " + persistent.scope().id());
                }
                yield new PersistentTarget(declaration);
            }
        };
    }

    /** Parses the value for the operation and checks a literal against the declared type. */
    private static Value valueOf(ParamReader params, VariableOp op, Target target) {
        if (!op.takesValue()) {
            if (params.has("value")) {
                throw new IllegalArgumentException(op.id() + " takes no value");
            }
            Value none = new Literal(null);
            if (target instanceof PersistentTarget persistent) {
                checkLiteral(persistent.declaration(), op, none); // toggle on a number is still refused
            }
            return none;
        }
        if (!params.has("value")) {
            if (target instanceof LocalTarget && op == VariableOp.SET) {
                return new Literal(null); // the M2 shape: a bare name with no value stores nothing
            }
            throw new IllegalArgumentException("value is required for " + op.id());
        }
        Value value = valueFromText(params);
        if (target instanceof PersistentTarget persistent) {
            checkLiteral(persistent.declaration(), op, value);
        }
        return value;
    }

    private static Value valueFromText(ParamReader params) {
        Object raw = params.raw("value").orElse(null);
        if (raw instanceof String text && text.contains("${")) {
            return new Templated(params.template("value"));
        }
        if (raw instanceof String text && looksLikeExpression(text)) {
            return new Computed(params.expression("value"));
        }
        return new Literal(raw);
    }

    /**
     * Checks the operation and a literal value against the declared type now. A template or an
     * expression is checked when it runs, because its type is only known then.
     */
    private static void checkLiteral(VariableDeclaration declaration, VariableOp op, Value value) {
        VariableType type = declaration.type();
        if (op == VariableOp.TOGGLE && type != VariableType.BOOLEAN) {
            throw mismatch(declaration, "toggle works on boolean variables");
        }
        if ((op == VariableOp.ADD || op == VariableOp.SUBTRACT || op == VariableOp.MULTIPLY)
                && type != VariableType.NUMBER) {
            throw mismatch(declaration, op.id() + " works on number variables");
        }
        if (value instanceof Literal literal && literal.value() != null && !type.accepts(literal.value())) {
            throw mismatch(declaration, "value '" + literal.value() + "' is not a " + type.id());
        }
    }

    private static ParseRejection mismatch(VariableDeclaration declaration, String reason) {
        return new ParseRejection(DiagnosticCodes.VARIABLE_TYPE_MISMATCH,
                declaration.id() + " is declared " + declaration.type().id() + ": " + reason);
    }

    // --- run ----------------------------------------------------------------------

    private static ActionOutcome writeLocal(String name, Params params, LogicContext context) {
        var locals = context.locals();
        if (params.op() == VariableOp.REMOVE || params.op() == VariableOp.RESET) {
            locals.remove(name);
            return ActionOutcome.OK;
        }
        var result = VariableOps.apply(params.op(), null, locals.get(name), operand(params, context));
        if (!result.ok()) {
            return ActionOutcome.failed(name + ": " + result.reason());
        }
        locals.put(name, result.value());
        return ActionOutcome.OK;
    }

    private static ActionOutcome writeSession(String name, Params params, LogicContext context) {
        var session = context.facet(VariableFacets.SESSION);
        if (session.isEmpty()) {
            return ActionOutcome.failed("session." + name + ": no open gui");
        }
        SessionVariables variables = session.get();
        if (params.op() == VariableOp.REMOVE || params.op() == VariableOp.RESET) {
            variables.put(name, null);
            return ActionOutcome.OK;
        }
        var result = VariableOps.apply(params.op(), null, variables.get(name).orElse(null), operand(params, context));
        if (!result.ok()) {
            return ActionOutcome.failed("session." + name + ": " + result.reason());
        }
        variables.put(name, result.value());
        return ActionOutcome.OK;
    }

    private static ActionOutcome writePersistent(VariableDeclaration declaration, Params params, LogicContext context) {
        VariableBinding binding = context.variables();
        // only the arithmetic and toggle read the current value; a set replaces it
        boolean needsCurrent = params.op() == VariableOp.ADD || params.op() == VariableOp.SUBTRACT
                || params.op() == VariableOp.MULTIPLY || params.op() == VariableOp.TOGGLE;
        Object current = needsCurrent
                ? binding.stored(declaration).orElse(declaration.defaultValue())
                : null;
        var result = VariableOps.apply(params.op(), declaration.type(), current, operand(params, context));
        if (!result.ok()) {
            return ActionOutcome.failed(declaration.id() + ": " + result.reason());
        }
        if (!binding.store(declaration, result.value())) {
            return ActionOutcome.failed(declaration.id() + ": no holder for this " + declaration.scope().id()
                    + " variable (is the player online?)");
        }
        return ActionOutcome.OK;
    }

    /** The operand of the step, evaluated now. */
    private static Object operand(Params params, LogicContext context) {
        return switch (params.value()) {
            case Literal literal -> literal.value();
            case Templated templated -> templated.text().render(context);
            case Computed computed -> computed.expression().evaluate(context);
        };
    }

    /** Arithmetic or logic intent: a trailing operator, a trailing {@code &&}/{@code ||}, or an equality test. */
    private static boolean looksLikeExpression(String text) {
        return text.matches(".*[+\\-*/%<>!]|.*&&|.*\\|\\||.*(==|!=).*");
    }
}
