package dev.materione.behavior.flow;

import dev.materione.api.logic.TriggerDescription;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.behavior.rule.RuleFilters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the filter keys a rule sets on its trigger (logic.md §4.3): exactly the keys the trigger's
 * description declares in {@link TriggerDescription#filters()}, typed by that schema, so the
 * compiler, the studio and the firing site share one definition (D5). Each filter is a value or a
 * list of values; an enum filter accepts only its declared values, an integer filter whole numbers
 * from the schema's minimum, and a filter the schema marks required must be written (a
 * {@code gui.tick} without {@code every:} has nothing to wait for). A key the trigger does not declare
 * is not a filter and is ignored, like any other key this version does not know.
 *
 * <p>A filter the author wrote and the schema rejects makes the rule unreadable (it would match
 * something other than what was meant), so the rule is skipped with {@code MTR-0021} at the key's
 * path rather than loaded with a filter dropped.
 */
final class TriggerFilters {

    private TriggerFilters() {}

    /**
     * @param rule the authored rule mapping
     * @param trigger the rule's trigger, when the lookup can describe it
     * @throws Skip when a declared filter is malformed (already reported)
     */
    static RuleFilters read(
            Map<String, Object> rule, Optional<TriggerDescription> trigger, CompileUnit unit, StepPath path) {
        if (trigger.isEmpty() || trigger.get().filters().isEmpty()) {
            return RuleFilters.none();
        }
        var values = new LinkedHashMap<String, List<String>>();
        for (ParameterSchema schema : trigger.get().filters()) {
            if (!rule.containsKey(schema.id())) {
                if (schema.required()) {
                    throw unit.structural(path, "'" + trigger.get().self().displayName() + "' needs '"
                            + schema.id() + ":'", schema.description() == null ? "add " + schema.id() + ":"
                            : schema.description());
                }
                continue;
            }
            values.put(schema.id(), values(schema, rule.get(schema.id()), unit, path.child(schema.id())));
        }
        return values.isEmpty() ? RuleFilters.none() : new RuleFilters(values);
    }

    private static List<String> values(ParameterSchema schema, Object raw, CompileUnit unit, StepPath path) {
        List<Object> items = Shapes.listOf(raw);
        if (items.isEmpty()) {
            throw unit.structural(path, "'" + schema.id() + "' is empty",
                    "name at least one value, or remove the filter to match everything");
        }
        var out = new ArrayList<String>();
        for (Object item : items) {
            out.add(value(schema, item, unit, path));
        }
        return out;
    }

    private static String value(ParameterSchema schema, Object item, CompileUnit unit, StepPath path) {
        if (schema.type() == ParameterType.INTEGER) {
            Long whole = whole(item);
            if (whole == null) {
                throw unit.structural(path, "'" + schema.id() + "' must be a whole number, not '" + item + "'",
                        "write it as a number, e.g. " + schema.id() + ": 20");
            }
            Double min = schema.editor().min();
            if (min != null && whole < min) {
                throw unit.structural(path, "'" + schema.id() + "' must be at least " + min.longValue(),
                        "raise " + schema.id() + " to " + min.longValue() + " or more");
            }
            return Long.toString(whole);
        }
        if (!(item instanceof String text) || text.isBlank()) {
            throw unit.structural(path, "'" + schema.id() + "' must be text, not '" + item + "'",
                    "write the value as text");
        }
        String trimmed = text.trim();
        if (schema.type() == ParameterType.ENUM && !schema.allowedValues().isEmpty()) {
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (!schema.allowedValues().contains(lower)) {
                throw unit.structural(path, "unknown " + schema.id() + " '" + trimmed + "'",
                        "use one of: " + String.join(", ", schema.allowedValues()));
            }
            return lower;
        }
        return trimmed;
    }

    private static Long whole(Object item) {
        if (Shapes.isWhole(item)) {
            return ((Number) item).longValue();
        }
        return null;
    }
}
