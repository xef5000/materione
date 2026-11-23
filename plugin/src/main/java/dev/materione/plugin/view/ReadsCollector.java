package dev.materione.plugin.view;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.behavior.expr.ExprNode;
import dev.materione.behavior.flow.AllCond;
import dev.materione.behavior.flow.AnyCond;
import dev.materione.behavior.flow.AtLeastCond;
import dev.materione.behavior.flow.Condition;
import dev.materione.behavior.flow.ExpressionCond;
import dev.materione.behavior.flow.NotCond;
import dev.materione.behavior.flow.PredicateCond;
import dev.materione.behavior.interp.Template;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.plugin.logic.condition.VarCondition;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts what one view definition reads from its compiled templates and conditions (views.md section 5.2). A kind
 * feeds it every template source, expression tree and condition tree of the definition, each with the {@link ReadRole}
 * it is read in; {@link #result()} is the definition's {@link ViewReads}, and {@link #problems()} what the extraction
 * found wrong or could not place.
 *
 * <p>A read of {@code vars.<scope>.<pack>.<key>} names a family. The same path with too few segments cannot be
 * placed: it is polled and reported once as {@code MTR-0127}. A session read that names no declared session variable
 * is {@code MTR-0063}, the code a {@code set_var} or a {@code var} condition on it already carries; other scopes read
 * empty when undeclared, as everywhere. Anything the writes do not announce (a world fact, a {@code %token%}, a
 * predicate such as {@code has_money}) only marks the definition polled.
 */
public final class ReadsCollector {

    private final NamespacedId definition;
    private final LogicLookup lookup;
    private final Map<ReadKey, Set<ReadRole>> keys = new LinkedHashMap<>();
    private final Set<String> reported = new LinkedHashSet<>();
    private final List<Diagnostic> problems = new ArrayList<>();
    private boolean polled;

    /**
     * @param definition the definition being read, the resource of every problem
     * @param lookup the registries: declared variables and the functions an expression may call
     */
    public ReadsCollector(NamespacedId definition, LogicLookup lookup) {
        this.definition = definition;
        this.lookup = lookup;
    }

    /** Reads every {@code ${expression}} of a template, and marks it polled when it holds a {@code %token%}. */
    public ReadsCollector template(String source, ReadRole role) {
        if (source == null || source.isEmpty()) {
            return this;
        }
        if (source.indexOf('%') >= 0) {
            polled = true;
        }
        if (source.indexOf("${") < 0) {
            return this;
        }
        try {
            for (var expression : Template.compile(source, lookup.functions()).expressions()) {
                expression(expression.tree(), role);
            }
        } catch (IllegalArgumentException malformed) {
            // the compile stage reported the malformed template; it contributes no reads
        }
        return this;
    }

    /** Reads the variable paths of one expression tree. */
    public ReadsCollector expression(ExprNode node, ReadRole role) {
        switch (node) {
            case ExprNode.Path path -> path(path.segments(), role);
            case ExprNode.Unary unary -> expression(unary.operand(), role);
            case ExprNode.Binary binary -> {
                expression(binary.left(), role);
                expression(binary.right(), role);
            }
            case ExprNode.Ternary ternary -> {
                expression(ternary.condition(), role);
                expression(ternary.ifTrue(), role);
                expression(ternary.ifFalse(), role);
            }
            case ExprNode.Call call -> {
                polled = true; // a function may read a provider or the world; its arguments are read as written
                call.arguments().forEach(argument -> expression(argument, role));
            }
            case ExprNode.Num ignored -> { }
            case ExprNode.Str ignored -> { }
            case ExprNode.Bool ignored -> { }
            case ExprNode.Null ignored -> { }
        }
        return this;
    }

    /**
     * Reads a compiled condition tree: an expression leaf like any expression, a {@code var} condition as the
     * variable it names, any other predicate as polled (it reads live player or world state).
     */
    public ReadsCollector condition(Condition condition, ReadRole role) {
        switch (condition) {
            case null -> { }
            case ExpressionCond leaf -> expression(leaf.tree(), role);
            case AllCond all -> all.of().forEach(inner -> condition(inner, role));
            case AnyCond any -> any.of().forEach(inner -> condition(inner, role));
            case AtLeastCond atLeast -> atLeast.of().forEach(inner -> condition(inner, role));
            case NotCond not -> condition(not.inner(), role);
            case PredicateCond<?> predicate -> {
                if (predicate.params() instanceof VarCondition.Params var) {
                    if (var.source() instanceof VarCondition.DeclaredSource declared) {
                        declared(declared.declaration().scope(), declared.declaration().id(), role);
                    }
                    template(var.value().source(), role);
                } else {
                    polled = true;
                }
            }
        }
        return this;
    }

    /**
     * Reads the variable a source names by its id alone, in whatever scope it is declared. An undeclared one reads
     * nothing here: the source reports it when it runs.
     */
    public ReadsCollector declaredVariable(NamespacedId id, ReadRole role) {
        lookup.variable(id).ifPresent(declaration -> declared(declaration.scope(), id, role));
        return this;
    }

    /**
     * Reads the session variable a source names by key alone ({@code search.var}, the content list's {@code search}):
     * the one declared in {@code pack}. One that is not declared is {@code MTR-0063}.
     */
    public ReadsCollector sessionVariable(String key, String pack, ReadRole role) {
        NamespacedId id;
        try {
            id = NamespacedId.of(pack, key);
        } catch (IllegalArgumentException malformed) {
            problem(DiagnosticCodes.VARIABLE_UNDECLARED, Severity.WARN,
                    "'" + key + "' does not name a session variable of pack " + pack,
                    "name a session variable by its key");
            return this;
        }
        if (declaredAs(id, VariableScope.SESSION)) {
            declared(VariableScope.SESSION, id, role);
        } else {
            problem(DiagnosticCodes.VARIABLE_UNDECLARED, Severity.WARN,
                    "'" + key + "' is not a declared session variable of pack " + pack,
                    "declare " + id + " with scope: session in a variables file of its pack");
        }
        return this;
    }

    /** Marks the definition as reading something no write announces. */
    public ReadsCollector polled() {
        polled = true;
        return this;
    }

    /** What the definition reads. */
    public ViewReads result() {
        return new ViewReads(keys, polled);
    }

    /** The problems found: {@code MTR-0063} for an undeclared session read, {@code MTR-0127} for an unplaced one. */
    public List<Diagnostic> problems() {
        return List.copyOf(problems);
    }

    private void path(List<String> segments, ReadRole role) {
        if (segments.isEmpty()) {
            return;
        }
        String root = segments.get(0);
        if (!"vars".equals(root)) {
            if ("player".equals(root) || "server".equals(root) || "item".equals(root)) {
                polled = true; // facts the engine does not announce
            }
            return;
        }
        if (segments.size() < 2) {
            return;
        }
        VariableScope scope = VariableScope.of(segments.get(1)).orElse(null);
        if (scope == null) {
            return; // a run's local
        }
        if (segments.size() < 4) {
            unplaced(scope, String.join(".", segments));
            return;
        }
        NamespacedId id;
        try {
            id = NamespacedId.of(segments.get(2), segments.get(3));
        } catch (IllegalArgumentException malformed) {
            unplaced(scope, String.join(".", segments));
            return;
        }
        if (scope == VariableScope.SESSION && !declaredAs(id, VariableScope.SESSION)) {
            problem(DiagnosticCodes.VARIABLE_UNDECLARED, Severity.WARN,
                    "'" + String.join(".", segments) + "' is not a declared session variable",
                    "declare " + id + " with scope: session in a variables file of its pack");
            return;
        }
        declared(scope, id, role);
    }

    private void declared(VariableScope scope, NamespacedId id, ReadRole role) {
        keys.computeIfAbsent(new ReadKey(scope, id), ignored -> EnumSet.noneOf(ReadRole.class)).add(role);
    }

    private boolean declaredAs(NamespacedId id, VariableScope scope) {
        return lookup.variable(id).map(VariableDeclaration::scope).filter(scope::equals).isPresent();
    }

    private void unplaced(VariableScope scope, String path) {
        polled = true;
        if (scope == VariableScope.SESSION) {
            problem(DiagnosticCodes.VARIABLE_UNDECLARED, Severity.WARN,
                    "'" + path + "' does not name a declared session variable",
                    "read a session variable as vars.session.<pack>.<key>");
        } else {
            problem(DiagnosticCodes.VIEW_READ_UNPLACED, Severity.INFO,
                    "'" + path + "' names no declared variable the view can watch, so it updates on the view's clock only",
                    "write vars." + scope.id() + ".<pack>.<key>, or add a refresh clock");
        }
    }

    private void problem(dev.materione.api.diagnostics.DiagnosticCode code, Severity severity, String message,
            String hint) {
        if (reported.add(code.code() + message)) {
            problems.add(new Diagnostic(code, severity, "view " + definition + ": " + message, definition, null, hint));
        }
    }
}
