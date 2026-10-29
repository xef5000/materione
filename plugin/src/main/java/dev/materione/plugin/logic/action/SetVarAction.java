package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.ValueExpression;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;

/**
 * {@code set_var: {name: count, value: "${vars.count + 1}"}}: stores a value for the rest of this
 * event chain (the run-local {@code vars.*} scope). Persistent variables with declared types and
 * scopes replace this with the variables work (logic.md §3, §13 L5).
 *
 * <p>The value is decided once, at load, by how it is written: a string with {@code ${...}} is a
 * template (the result is text); a string that reads as an arithmetic or logic expression is
 * evaluated to a typed value; anything else is stored as written.
 */
public final class SetVarAction implements ActionType<SetVarAction.Params> {

    /** How the value is produced when the step runs. */
    public sealed interface Value permits Literal, Templated, Computed {}

    /** Stored as written (numbers, booleans, plain strings, lists). */
    public record Literal(Object value) implements Value {}

    /** A string with {@code ${...}}: rendered to text each run. */
    public record Templated(TemplateText text) implements Value {}

    /** A bare expression: evaluated to a typed value each run. */
    public record Computed(ValueExpression expression) implements Value {}

    /** The parsed step. */
    public record Params(String name, Value value) {}

    @Override
    public String id() {
        return "set_var";
    }

    @Override
    public ActionDescription describe() {
        return ActionDescription.of(new SelfDescription("Set variable",
                "Stores a value for the rest of this event chain",
                List.of(
                        new ParameterSchema("name", ParameterType.STRING, true, null, "Variable name", null, null),
                        new ParameterSchema("value", ParameterType.STRING, false, null,
                                "Value or ${expression}", null, null)),
                "2.0.0"), LogicCategory.DATA);
    }

    @Override
    public Threading threading() {
        return Threading.ANY;
    }

    @Override
    public Params parse(ParamReader params) {
        String name = params.text("name");
        Object raw = params.raw("value").orElse(null);
        Value value;
        if (raw instanceof String text && text.contains("${")) {
            value = new Templated(params.template("value"));
        } else if (raw instanceof String text && looksLikeExpression(text)) {
            value = new Computed(params.expression("value"));
        } else {
            value = new Literal(raw);
        }
        return new Params(name, value);
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Object value = switch (params.value()) {
            case Literal literal -> literal.value();
            case Templated templated -> templated.text().render(context);
            case Computed computed -> computed.expression().evaluate(context);
        };
        context.locals().put(params.name(), value);
        return ActionOutcome.OK;
    }

    /** Arithmetic or logic intent: a trailing operator, a trailing {@code &&}/{@code ||}, or an equality test. */
    private static boolean looksLikeExpression(String text) {
        return text.matches(".*[+\\-*/%<>!]|.*&&|.*\\|\\||.*(==|!=).*");
    }
}
