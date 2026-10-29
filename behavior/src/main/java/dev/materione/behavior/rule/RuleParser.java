package dev.materione.behavior.rule;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.behavior.expr.ExprNode;
import dev.materione.behavior.expr.ExpressionException;
import dev.materione.behavior.expr.ExpressionParser;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.LogicLookup;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a resource's {@code behavior:} section into loaded rules
 * (runtime-systems §2.1). Pure: problems come back as diagnostics for the
 * caller to publish — the parser never touches the bus, the filesystem or
 * the platform. One broken rule is skipped; its siblings load (fail-soft).
 *
 * <p>Actions and triggers are resolved through the registries ({@link LogicLookup}) and parsed
 * once, here: a rule holds typed {@link BoundAction}s, not raw maps (logic.md §4.1). A structural
 * problem (not a mapping, no action key, a bad {@code chance}) skips the rule with
 * {@code MTR-0021}; a single step whose action is unknown or rejects its parameters is skipped
 * alone with {@code MTR-0056}, and the rule keeps its other steps.
 */
public final class RuleParser {

    /** Self-description of the rule option keys {@link #parseOptions} reads. */
    public static final List<ParameterSchema> RULE_OPTIONS = List.of(
            option("id", ParameterType.STRING, null,
                    "Stable rule name used in diagnostics and cooldown keys", List.of()),
            option("cooldown", ParameterType.INTEGER, "0",
                    "Milliseconds before this rule can fire again", List.of()),
            option("charges", ParameterType.INTEGER, null,
                    "How many times the rule can fire in total", List.of()),
            option("per", ParameterType.ENUM, RuleOptions.PER_PLAYER,
                    "Whether cooldown and charges count per player or for the whole server",
                    List.of(RuleOptions.PER_PLAYER, RuleOptions.PER_GLOBAL)));

    /** Self-description of the universal action keys {@link #parseShape} reads. */
    public static final List<ParameterSchema> ACTION_OPTIONS = List.of(
            option(BoundAction.CHANCE_KEY, ParameterType.DECIMAL, "1",
                    "Probability from 0 to 1 that the action runs", List.of()),
            option(BoundAction.DELAY_KEY, ParameterType.INTEGER, "0",
                    "Ticks to wait before the action runs", List.of()));

    private static ParameterSchema option(
            String id, ParameterType type, String defaultValue, String description, List<String> allowed) {
        return new ParameterSchema(id, type, false, defaultValue, description, allowed, List.of());
    }

    private RuleParser() {}

    /**
     * Parses {@code behavior:} — a list of rule maps ({@code on:/if:/do:/opts}).
     * Never returns null; invalid rules surface in {@link ParsedRules#problems()}.
     *
     * @param lookup the registries actions and triggers are resolved against
     */
    public static ParsedRules compile(NamespacedId owner, Object behaviorSection, LogicLookup lookup) {
        if (!(behaviorSection instanceof List<?> entries)) {
            return ParsedRules.empty();
        }
        var rules = new ArrayList<Rule>();
        var problems = new ArrayList<Diagnostic>();
        int index = 0;
        for (Object entry : entries) {
            String fallbackId = owner + "#" + index++;
            if (!(entry instanceof Map<?, ?> raw)) {
                problems.add(ruleProblem(owner, fallbackId,
                        "rule is not a mapping", "every rule needs on:/do: keys"));
                continue;
            }
            parseRule(owner, fallbackId, asStringMap(raw), lookup, problems).ifPresent(rules::add);
        }
        return new ParsedRules(List.copyOf(rules), List.copyOf(problems));
    }

    private static Optional<Rule> parseRule(
            NamespacedId owner,
            String fallbackId,
            Map<String, Object> raw,
            LogicLookup lookup,
            List<Diagnostic> problems) {
        String id = raw.get("id") instanceof String named && !named.isBlank() ? named : fallbackId;
        String selectorText = text(raw.get("on"));
        if (selectorText == null) {
            problems.add(ruleProblem(owner, id, "missing 'on:' event selector",
                    "use an event from the taxonomy, e.g. on: click:right"));
            return Optional.empty();
        }
        Optional<EventSelector> selector = lookup.selector(selectorText);
        if (selector.isEmpty()) {
            problems.add(ruleProblem(owner, id, "unknown event type '" + typeOf(selectorText) + "'",
                    "see the event taxonomy (runtime-systems §2.1): "
                            + "click, break, place, interact, equip, unequip, drop, pickup, consume, damage, death…"));
            return Optional.empty();
        }

        var conditions = new ArrayList<ExprNode>();
        for (Object condition : listOf(raw.get("if"))) {
            String source = text(condition);
            if (source == null) {
                continue;
            }
            try {
                conditions.add(parseCondition(source));
            } catch (ExpressionException e) {
                problems.add(new Diagnostic(
                        DiagnosticCodes.EXPRESSION_INVALID,
                        Severity.WARN,
                        owner + " rule '" + id + "': " + e.getMessage(),
                        owner, null, "fix the expression syntax; the rule was skipped"));
                return Optional.empty();
            }
        }

        var actions = new ArrayList<BoundAction<?>>();
        List<Object> steps = listOf(raw.get("do"));
        for (Object entry : steps) {
            if (!(entry instanceof Map<?, ?> rawAction)) {
                problems.add(ruleProblem(owner, id, "'do:' entry is not a mapping",
                        "each action is a mapping like { run_command: \"say hi\" }"));
                return Optional.empty();
            }
            ActionShape shape;
            try {
                shape = parseShape(asStringMap(rawAction));
            } catch (RuntimeException e) {
                problems.add(ruleProblem(owner, id, "bad action: " + e.getMessage(),
                        "check the action's type key and params; the whole rule was skipped"));
                return Optional.empty();
            }
            bind(owner, id, shape, lookup, problems).ifPresent(actions::add);
        }
        if (actions.isEmpty()) {
            problems.add(steps.isEmpty()
                    ? (raw.get("do") == null
                            ? ruleProblem(owner, id, "missing 'do:' actions", "add at least one action")
                            : ruleProblem(owner, id, "'do:' is empty", "add at least one action"))
                    : ruleProblem(owner, id, "none of its actions loaded",
                            "fix the actions reported above; the rule was skipped"));
            return Optional.empty();
        }

        RuleOptions options = parseOptions(raw, owner, id, problems);
        return Optional.of(new Rule(owner, id, selector.get(), conditions, actions, options));
    }

    /** What the author wrote for one action, before the type has seen it. */
    private record ActionShape(String type, Object payload, double chance, long delayTicks) {}

    /**
     * An action map carries exactly one non-property key (the action type)
     * plus optional {@code chance}/{@code delay}; the value is either a scalar
     * shorthand or a params map.
     */
    private static ActionShape parseShape(Map<String, Object> raw) {
        String type = null;
        Object payload = null;
        double chance = 1.0;
        long delayTicks = 0;
        for (var entry : raw.entrySet()) {
            if (entry.getKey().equals(BoundAction.CHANCE_KEY)) {
                chance = requireRatio(entry.getValue(), BoundAction.CHANCE_KEY);
            } else if (entry.getKey().equals(BoundAction.DELAY_KEY)) {
                delayTicks = requireNonNegativeLong(entry.getValue(), BoundAction.DELAY_KEY);
            } else if (type == null) {
                type = entry.getKey();
                payload = entry.getValue();
            } else {
                throw new IllegalArgumentException(
                        "multiple action keys ('" + type + "', '" + entry.getKey() + "')");
            }
        }
        if (type == null) {
            throw new IllegalArgumentException("no action type key");
        }
        return new ActionShape(type, payload, chance, delayTicks);
    }

    /**
     * Resolves the action and runs its typed parse. A step the registry does not know, or whose
     * type rejects the parameters, is diagnosed ({@code MTR-0056}) and dropped on its own.
     */
    private static Optional<BoundAction<?>> bind(
            NamespacedId owner, String ruleId, ActionShape shape, LogicLookup lookup, List<Diagnostic> problems) {
        Optional<ActionType<?>> resolved = lookup.action(shape.type());
        if (resolved.isEmpty()) {
            problems.add(stepProblem(owner, ruleId, "unknown action '" + shape.type() + "'",
                    "check the spelling or install the addon that provides it; only this action was skipped"));
            return Optional.empty();
        }
        try {
            return Optional.of(bindTyped(resolved.get(), shape));
        } catch (RuntimeException e) {
            problems.add(stepProblem(owner, ruleId, "action '" + shape.type() + "' rejected: " + e.getMessage(),
                    "fix this action's parameters; only this action was skipped"));
            return Optional.empty();
        }
    }

    /** Captures the type's parameter type so parse and bind agree on it. */
    private static <P> BoundAction<P> bindTyped(ActionType<P> type, ActionShape shape) {
        ParamReader reader = ParamReader.of(paramsOf(type, shape), EngineExpressions.INSTANCE);
        return BoundAction.bind(shape.type(), type, reader, shape.chance(), shape.delayTicks());
    }

    /** Normalises the author's two spellings: a map is the parameters, a scalar is the shorthand parameter. */
    private static Map<String, Object> paramsOf(ActionType<?> type, ActionShape shape) {
        Object payload = shape.payload();
        if (payload instanceof Map<?, ?> mapping) {
            return asStringMap(mapping);
        }
        var params = new LinkedHashMap<String, Object>();
        if (payload != null) {
            String shorthand = type.describe().shorthand();
            if (shorthand == null) {
                throw new IllegalArgumentException(
                        "takes a mapping of parameters, not a bare value (e.g. " + shape.type() + ": { … })");
            }
            params.put(shorthand, payload);
        }
        return params;
    }

    private static RuleOptions parseOptions(
            Map<String, Object> raw, NamespacedId owner, String id, List<Diagnostic> problems) {
        long cooldown = 0;
        Integer charges = null;
        String scope = RuleOptions.PER_PLAYER;
        if (raw.get("cooldown") instanceof Number number && number.longValue() >= 0) {
            cooldown = number.longValue();
        }
        if (raw.get("charges") instanceof Number number && number.intValue() > 0) {
            charges = number.intValue();
        }
        if (raw.get("per") instanceof String per && !per.isBlank()) {
            if (!per.equals(RuleOptions.PER_PLAYER) && !per.equals(RuleOptions.PER_GLOBAL)) {
                problems.add(ruleProblem(owner, id, "unknown 'per:' scope '" + per + "'",
                        "use player or global; defaulting to player"));
            } else {
                scope = per;
            }
        }
        return new RuleOptions(cooldown, charges, scope);
    }

    // --- helpers -------------------------------------------------------------

    static ExprNode parseCondition(String source) {
        // conditions are expressions whose result is truth-tested
        return ExpressionParser.parse(source).tree();
    }

    private static String typeOf(String selectorText) {
        int colon = selectorText.indexOf(':');
        return (colon < 0 ? selectorText : selectorText.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
    }

    private static Diagnostic ruleProblem(NamespacedId owner, String ruleId, String problem, String hint) {
        return new Diagnostic(
                DiagnosticCodes.BEHAVIOR_RULE_INVALID,
                Severity.WARN,
                owner + " rule '" + ruleId + "': " + problem,
                owner, null, hint);
    }

    private static Diagnostic stepProblem(NamespacedId owner, String ruleId, String problem, String hint) {
        return new Diagnostic(
                DiagnosticCodes.LOGIC_STEP_REJECTED,
                Severity.WARN,
                owner + " rule '" + ruleId + "': " + problem,
                owner, null, hint);
    }

    private static double requireRatio(Object value, String key) {
        if (value instanceof Number number && number.doubleValue() >= 0 && number.doubleValue() <= 1) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException("'" + key + "' must be between 0 and 1");
    }

    private static long requireNonNegativeLong(Object value, String key) {
        if (value instanceof Number number && number.longValue() >= 0) {
            return number.longValue();
        }
        throw new IllegalArgumentException("'" + key + "' must be a non-negative number of ticks");
    }

    private static List<Object> listOf(Object value) {
        if (value instanceof List<?> list) {
            return List.copyOf(list);
        }
        return value != null ? List.of(value) : List.of();
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static Map<String, Object> asStringMap(Map<?, ?> raw) {
        var out = new LinkedHashMap<String, Object>();
        for (var entry : raw.entrySet()) {
            if (entry.getKey() instanceof String key) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }
}
