package dev.materione.behavior.rule;

import dev.materione.api.NamespacedId;
import dev.materione.behavior.expr.ExprNode;
import dev.materione.behavior.expr.ExpressionException;
import dev.materione.behavior.expr.ExpressionParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a resource's {@code behavior:} section into loaded rules
 * (runtime-systems §2.1). Pure: problems come back as diagnostics for the
 * caller to publish — the parser never touches the bus, the filesystem or
 * the platform. One broken rule is skipped; its siblings load (fail-soft).
 */
public final class RuleParser {

    /** Keys on an action map that are universal properties, never action types. */
    private static final Set<String> ACTION_PROPERTY_KEYS = Set.of(
            ActionSpec.CHANCE_KEY, ActionSpec.DELAY_KEY);

    /** Rule-level option keys. */
    private static final Set<String> RULE_OPTION_KEYS = Set.of("cooldown", "charges", "per", "id");

    private RuleParser() {}

    /**
     * Parses {@code behavior:} — a list of rule maps ({@code on:/if:/do:/opts}).
     * Never returns null; invalid rules surface in {@link ParsedRules#problems()}.
     */
    public static ParsedRules compile(NamespacedId owner, Object behaviorSection) {
        if (!(behaviorSection instanceof List<?> entries)) {
            return ParsedRules.empty();
        }
        var rules = new ArrayList<Rule>();
        var problems = new ArrayList<dev.materione.api.diagnostics.Diagnostic>();
        int index = 0;
        for (Object entry : entries) {
            String fallbackId = owner + "#" + index++;
            if (!(entry instanceof Map<?, ?> raw)) {
                problems.add(ruleProblem(owner, fallbackId,
                        "rule is not a mapping", "every rule needs on:/do: keys"));
                continue;
            }
            parseRule(owner, fallbackId, asStringMap(raw), problems).ifPresent(rules::add);
        }
        return new ParsedRules(List.copyOf(rules), List.copyOf(problems));
    }

    private static java.util.Optional<Rule> parseRule(
            NamespacedId owner,
            String fallbackId,
            Map<String, Object> raw,
            List<dev.materione.api.diagnostics.Diagnostic> problems) {
        String id = raw.get("id") instanceof String named && !named.isBlank() ? named : fallbackId;
        String selectorText = text(raw.get("on"));
        if (selectorText == null) {
            problems.add(ruleProblem(owner, id, "missing 'on:' event selector",
                    "use an event from the taxonomy, e.g. on: click:right"));
            return java.util.Optional.empty();
        }
        EventSelector selector;
        try {
            selector = parseSelector(selectorText);
        } catch (IllegalArgumentException e) {
            problems.add(ruleProblem(owner, id, e.getMessage(),
                    "see the event taxonomy (runtime-systems §2.1): "
                            + "click, break, place, interact, equip, unequip, drop, pickup, consume, damage, death…"));
            return java.util.Optional.empty();
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
                problems.add(new dev.materione.api.diagnostics.Diagnostic(
                        dev.materione.core.diagnostics.DiagnosticCodes.EXPRESSION_INVALID,
                        dev.materione.api.diagnostics.Severity.WARN,
                        owner + " rule '" + id + "': " + e.getMessage(),
                        owner, null, "fix the expression syntax; the rule was skipped"));
                return java.util.Optional.empty();
            }
        }

        var actions = new ArrayList<ActionSpec>();
        for (Object entry : listOf(raw.get("do"))) {
            if (!(entry instanceof Map<?, ?> rawAction)) {
                problems.add(ruleProblem(owner, id, "'do:' entry is not a mapping",
                        "each action is a mapping like { run_command: \"say hi\" }"));
                return java.util.Optional.empty();
            }
            try {
                actions.add(parseAction(asStringMap(rawAction)));
            } catch (RuntimeException e) {
                problems.add(ruleProblem(owner, id, "bad action: " + e.getMessage(),
                        "check the action's type key and params; the whole rule was skipped"));
                return java.util.Optional.empty();
            }
        }
        if (actions.isEmpty()) {
            problems.add(raw.get("do") == null
                    ? ruleProblem(owner, id, "missing 'do:' actions", "add at least one action")
                    : ruleProblem(owner, id, "'do:' is empty", "add at least one action"));
            return java.util.Optional.empty();
        }

        RuleOptions options = parseOptions(raw, owner, id, problems);
        return java.util.Optional.of(new Rule(owner, id, selector, conditions, actions, options));
    }

    private static EventSelector parseSelector(String text) {
        int colon = text.indexOf(':');
        String type = (colon < 0 ? text : text.substring(0, colon)).trim().toLowerCase(java.util.Locale.ROOT);
        String detail = colon < 0 ? null : text.substring(colon + 1).trim();
        return new EventSelector(type, detail == null || detail.isBlank()
                ? java.util.Optional.empty()
                : java.util.Optional.of(detail));
    }

    /**
     * An action map carries exactly one non-property key (the action type)
     * plus optional {@code chance}/{@code delay}; the value is either a scalar
     * shorthand or a params map.
     */
    private static ActionSpec parseAction(Map<String, Object> raw) {
        String type = null;
        Object payload = null;
        double chance = 1.0;
        long delayTicks = 0;
        for (var entry : raw.entrySet()) {
            if (entry.getKey().equals(ActionSpec.CHANCE_KEY)) {
                chance = requireRatio(entry.getValue(), ActionSpec.CHANCE_KEY);
            } else if (entry.getKey().equals(ActionSpec.DELAY_KEY)) {
                delayTicks = requireNonNegativeLong(entry.getValue(), ActionSpec.DELAY_KEY);
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
        var params = new LinkedHashMap<String, Object>();
        if (payload instanceof Map<?, ?> mapping) {
            params.putAll(asStringMap(mapping));
        } else if (payload != null) {
            params.put("value", payload);
        }
        return new ActionSpec(type, params, chance, delayTicks);
    }

    private static RuleOptions parseOptions(
            Map<String, Object> raw,
            NamespacedId owner,
            String id,
            List<dev.materione.api.diagnostics.Diagnostic> problems) {
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

    private static dev.materione.api.diagnostics.Diagnostic ruleProblem(
            NamespacedId owner, String ruleId, String problem, String hint) {
        return new dev.materione.api.diagnostics.Diagnostic(
                dev.materione.core.diagnostics.DiagnosticCodes.BEHAVIOR_RULE_INVALID,
                dev.materione.api.diagnostics.Severity.WARN,
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

    @SuppressWarnings("unchecked")
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
