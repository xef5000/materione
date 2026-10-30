package dev.materione.behavior.flow;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.ConditionType;
import dev.materione.behavior.expr.ExprNode;
import dev.materione.behavior.expr.ExpressionException;
import dev.materione.behavior.expr.ExpressionParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Compiles a condition (logic.md §2.3) into the sealed {@link Condition} tree:
 *
 * <ul>
 *   <li>a string is an expression, written bare ({@code player.level >= 10}) or wrapped as a
 *       template ({@code "${player.level >= 10}"}); a boolean is a constant;
 *   <li>a list is {@code all};
 *   <li>a one-entry mapping is a composite ({@code all}, {@code any}, {@code not},
 *       {@code at_least}) or a registered predicate ({@code has_money: 250}).
 * </ul>
 *
 * The composite names are reserved: a registered condition that took one of them would be shadowed.
 * A malformed expression or an unknown or rejected predicate is reported and the step that holds
 * the condition is skipped; a shape that is not a condition at all skips the whole rule or flow.
 */
final class ConditionCompiler {

    private final CompileUnit unit;

    ConditionCompiler(CompileUnit unit) {
        this.unit = unit;
    }

    /**
     * @param raw what the author wrote
     * @param path where it was written, for diagnostics
     * @throws Skip after reporting, when the condition cannot be compiled
     */
    Condition compile(Object raw, StepPath path) {
        if (raw instanceof String text) {
            return expression(text, path);
        }
        if (raw instanceof Boolean flag) {
            return new ExpressionCond(flag.toString(), new ExprNode.Bool(flag));
        }
        if (raw instanceof List<?> list) {
            return new AllCond(children(list, path));
        }
        if (raw instanceof Map<?, ?> mapping) {
            return mapped(Shapes.stringMap(mapping), path);
        }
        throw unit.structural(path, "a condition must be an expression, a list or a mapping",
                "e.g. \"player.level >= 10\", or { has_permission: vip.use }");
    }

    private Condition mapped(Map<String, Object> mapping, StepPath path) {
        if (mapping.size() != 1) {
            throw unit.structural(path, "a condition mapping has exactly one key, found " + mapping.keySet(),
                    "combine several with all: [ ... ] or any: [ ... ]");
        }
        var only = mapping.entrySet().iterator().next();
        String key = only.getKey();
        Object value = only.getValue();
        StepPath at = path.child(key);
        return switch (key) {
            case "all" -> new AllCond(children(requireList(value, key, at), at));
            case "any" -> new AnyCond(children(requireList(value, key, at), at));
            case "not" -> new NotCond(compile(value, at));
            case "at_least" -> atLeast(value, at);
            default -> predicate(key, value, path);
        };
    }

    private Condition atLeast(Object value, StepPath at) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw unit.structural(at, "at_least needs { count: n, of: [ ... ] }", "e.g. at_least: { count: 2, of: [ ... ] }");
        }
        Map<String, Object> mapping = Shapes.stringMap(raw);
        Object count = mapping.get("count");
        if (!Shapes.isWhole(count) || ((Number) count).intValue() < 0 || !mapping.containsKey("of")
                || !Set.of("count", "of").containsAll(mapping.keySet())) {
            throw unit.structural(at, "at_least needs a whole 'count' of 0 or more and an 'of' list, nothing else",
                    "e.g. at_least: { count: 2, of: [ ... ] }");
        }
        StepPath of = at.child("of");
        return new AtLeastCond(((Number) count).intValue(), children(requireList(mapping.get("of"), "of", of), of));
    }

    private List<Condition> children(List<?> list, StepPath path) {
        var out = new ArrayList<Condition>();
        boolean failed = false;
        for (int i = 0; i < list.size(); i++) {
            try {
                out.add(compile(list.get(i), path.child(i)));
            } catch (Skip skip) {
                if (skip.scope() == Skip.Scope.UNIT) {
                    throw skip;
                }
                failed = true; // report every bad child, then fail the whole condition
            }
        }
        if (failed) {
            throw Skip.step();
        }
        return out;
    }

    private List<?> requireList(Object value, String key, StepPath at) {
        if (value instanceof List<?> list) {
            return list;
        }
        throw unit.structural(at, key + " needs a list of conditions", "e.g. " + key + ": [ ... ]");
    }

    // --- leaves -------------------------------------------------------------------

    private Condition expression(String text, StepPath path) {
        String source = unwrap(text.trim());
        try {
            ExprNode tree = ExpressionParser.parse(source).tree();
            unit.checkExpression(tree, path);
            return new ExpressionCond(source, tree);
        } catch (ExpressionException e) {
            unit.problem(DiagnosticCodes.EXPRESSION_INVALID, Severity.WARN, path, e.getMessage(),
                    "fix the expression syntax; the step that holds it was skipped");
            throw Skip.step();
        }
    }

    /** {@code "${a && b}"} is the template spelling of the expression {@code a && b}. */
    static String unwrap(String text) {
        if (text.startsWith("${") && text.endsWith("}") && text.indexOf('}') == text.length() - 1
                && text.indexOf("${", 2) < 0) {
            return text.substring(2, text.length() - 1).trim();
        }
        return text;
    }

    private Condition predicate(String key, Object payload, StepPath path) {
        Optional<ConditionType<?>> resolved = unit.context.lookup.condition(key);
        if (resolved.isEmpty()) {
            unit.problem(DiagnosticCodes.LOGIC_STEP_REJECTED, Severity.WARN, path,
                    "unknown condition '" + key + "'",
                    "check the spelling or install the addon that provides it; the step that holds it was skipped");
            throw Skip.step();
        }
        try {
            return bind(key, resolved.get(), payload, path);
        } catch (RuntimeException e) {
            unit.problem(DiagnosticCodes.LOGIC_STEP_REJECTED, Severity.WARN, path,
                    "condition '" + key + "' rejected: " + e.getMessage(),
                    "fix this condition's parameters; the step that holds it was skipped");
            throw Skip.step();
        }
    }

    /** Captures the type's parameter type so parse and bind agree on it. */
    private <P> Condition bind(String key, ConditionType<P> type, Object payload, StepPath path) {
        var recorder = new RecordingExpressions();
        var reader = Shapes.reader(Shapes.paramsOf(key, type.describe().shorthand(), payload), recorder);
        P params = type.parse(reader);
        if (params == null) {
            throw new IllegalArgumentException("the condition produced no parameters");
        }
        recorder.check(unit, path);
        return new PredicateCond<>(key, type, params);
    }
}
